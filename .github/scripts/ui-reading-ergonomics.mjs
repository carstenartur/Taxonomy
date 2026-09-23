/** Real application assertions after the existing complete Copilot workflow. */
export async function verifyReadingErgonomics({ page, evidence }) {
  const originalViewport = page.viewportSize();
  const originalView = await page.evaluate(() => window.TaxonomyState.currentView);
  const errors = [];
  const onError = error => errors.push(error.message);
  page.on('pageerror', onError);
  try {
    evidence.assert(await page.evaluate(() => {
      const duration = window.TaxonomyState.lastAnalysisDurationMillis;
      return Number.isSafeInteger(duration) && duration >= 0;
    }), 'Completed analysis must retain a measured duration');
    const originalData = await page.evaluate(() => JSON.stringify({
      tree: window.TaxonomyState.taxonomyData, scores: window.TaxonomyState.currentScores
    }));
    for (const width of [390, 768, 1366, 1920, 2560]) {
      await page.setViewportSize({ width, height: 1080 });
      const positions = [];
      for (const id of ['viewSunburst', 'viewTree', 'viewDecision', 'viewSummary']) {
        await page.locator('#' + id).click();
        positions.push(await page.locator('#viewList').evaluate(button => {
          const rect = button.getBoundingClientRect();
          const header = button.closest('.card-header').getBoundingClientRect();
          return { x: rect.x - header.x, y: rect.y - header.y };
        }));
      }
      for (const axis of ['x', 'y']) {
        evidence.assert(Math.max(...positions.map(p => p[axis])) - Math.min(...positions.map(p => p[axis])) < 1,
          `Diagram selector moves on ${axis} at ${width}px: ${JSON.stringify(positions)}`);
      }
    }
    await page.locator('#viewSummary').click();
    const selected = page.locator('.summary-layer-element').first();
    await selected.focus();
    await page.keyboard.press('Enter');
    evidence.assert(await page.evaluate(() => window.TaxonomyState.currentView === 'summary'),
      'Summary keyboard selection must not navigate to another diagram');
    evidence.assert((await page.locator('#summarySelectionDetails').innerText()).trim().length > 0,
      'Summary selection must expose its details');
    evidence.assert(await selected.getAttribute('aria-pressed') === 'true', 'Summary selection is not exposed');
    await page.locator('#viewSunburst').click();
    await page.locator('#sunburstHideZero').check();
    evidence.assert(await page.evaluate(() => JSON.stringify({
      tree: window.TaxonomyState.taxonomyData, scores: window.TaxonomyState.currentScores
    })) === originalData, 'Sunburst visibility filter changed source data');
    await page.locator('#sunburstHideZero').uncheck();
    // Native activation of an actual rendered arc must exercise zoom, not a replacement renderer.
    const hit = await page.locator('#taxonomyTree path').evaluateAll(paths => {
      for (const path of paths) {
        const d = path.__data__;
        if (!d?.children?.length || path.getAttribute('pointer-events') === 'none') continue;
        const current = d.current;
        const radius = (current.y0 + current.y1) / 2;
        const angle = (current.x0 + current.x1) / 2 - Math.PI / 2;
        const point = new DOMPoint(radius * Math.cos(angle), radius * Math.sin(angle))
          .matrixTransform(path.getScreenCTM());
        if (document.elementFromPoint(point.x, point.y) === path) return { x: point.x, y: point.y };
      }
      return null;
    });
    evidence.assert(hit !== null, 'Sunburst has no clickable parent arc');
    await page.mouse.click(hit.x, hit.y);
    evidence.assert(errors.length === 0, `Sunburst zoom threw: ${errors.join('; ')}`);
    evidence.assert(await page.locator('#analysisDurationDisplay').isVisible(), 'Measured duration disappeared on navigation');
    await page.locator('#leftPanel').scrollIntoViewIfNeeded();
    await evidence.saveRequiredViewportState('analysis-reading-ergonomics', '#leftPanel');
    evidence.passed('Stable diagram selection, Summary keyboard details, immutable Sunburst filter and analysis duration');
  } finally {
    page.off('pageerror', onError);
    if (originalViewport) await page.setViewportSize(originalViewport);
    await page.evaluate(view => window.TaxonomyBrowse.switchView(view), originalView);
  }
}
