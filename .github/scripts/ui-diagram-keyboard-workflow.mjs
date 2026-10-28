import { expect } from '@playwright/test';

const fixture = [{ code: 'QA-D', name: 'Original diagram root', children: [
  { code: 'QA-D-A', name: 'Original first branch', children: [
    { code: 'QA-D-A1', name: 'Original first leaf', children: [] },
    { code: 'QA-D-A2', name: 'Original second leaf', children: [] }
  ] },
  { code: 'QA-D-B', name: 'Original second branch', children: [
    { code: 'QA-D-B1', name: 'Original third leaf', children: [] }
  ] }
] }];
const scores = { 'QA-D': 95, 'QA-D-A': 90, 'QA-D-B': 85,
  'QA-D-A1': 80, 'QA-D-A2': 75, 'QA-D-B1': 70 };

/** Real D3 rendering, native focus/key events and local zoom controls. */
export async function runDiagramKeyboardWorkflow({ page, evidence }) {
  const container = page.locator('#taxonomyTree');
  await page.evaluate(() => {
    if (!document.getElementById('diagramRelevanceFilter')) {
      const source = document.createElement('select'); source.id = 'diagramRelevanceFilter'; source.hidden = true;
      source.append(new Option('All', 'all'), new Option('Relevant', 'positive')); document.body.append(source);
    }
  });
  async function render(method, options = {}) {
    await page.evaluate(({ method, fixture, scores, context }) => {
      const host = document.getElementById('taxonomyTree');
      if (context) { window.TaxonomyViews.prepareDiagramContext(host, context); }
      window.TaxonomyViews[method](host, fixture, scores);
    }, { method, fixture: options.fixture || fixture, scores: options.scores || scores, context: options.context });
  }
  const visibleNodes = () => container.locator('[data-diagram-node]:not([aria-hidden="true"])');
  const activeNode = () => container.locator('[data-diagram-node][tabindex="0"]');
  const button = name => container.locator('.tax-diagram-controls').getByRole('button', { name, exact: true });
  const scale = () => container.locator('svg, canvas').first().evaluate(surface => window.d3.zoomTransform(surface).k);
  const viewport = () => container.locator('svg, canvas').first().evaluate(surface => {
    const transform = window.d3.zoomTransform(surface);
    return { x: transform.x, y: transform.y, k: transform.k };
  });

  async function panDiagram() {
    const surface = container.locator('svg, canvas').first();
    await surface.scrollIntoViewIfNeeded();
    const position = await surface.evaluate(element => {
      const box = element.getBoundingClientRect();
      return { x: Math.max(12, box.left + 12), y: Math.max(90, box.top + 12) };
    });
    await page.mouse.move(position.x, position.y);
    await page.mouse.down();
    await page.mouse.move(position.x + 37, position.y + 23, { steps: 3 });
    await page.mouse.up();
  }

  async function verifySecondRender(method, canvas = false) {
    const branch = canvas ? container.locator('canvas') : container.locator('[data-diagram-node="QA-D-A"]');
    if (canvas) {
      await branch.focus();
      await page.keyboard.press('Home');
      await page.keyboard.press('ArrowDown');
    } else { await branch.focus(); }
    await page.keyboard.press('Enter');
    await expect(branch).toHaveAttribute('aria-expanded', 'false');
    if (!canvas) { await expect(container).toHaveAttribute('data-transitions-complete', 'true'); }
    await button('Vergrößern').click();
    await panDiagram();
    const previous = await viewport();
    evidence.assert(previous.k > 1 && (previous.x !== 0 || previous.y !== 0), method + ' must exercise a real non-default viewport');
    await render(method);
    await expect(branch).toHaveAttribute('aria-expanded', 'false');
    await expect.poll(viewport).toEqual(previous);
    if (canvas) { await expect(branch).toHaveAttribute('data-diagram-node', 'QA-D-A'); }
    else { await expect(visibleNodes()).toHaveCount(4); }
    await branch.focus();
    await page.keyboard.press('Enter');
    await expect(branch).toHaveAttribute('aria-expanded', 'true');
    await button('Zoom zurücksetzen').click();
    await render(method);
    await expect(button('Zoom zurücksetzen')).toBeFocused();
    evidence.passed(method + ' retains explicit collapse, zoom and pan across a second render');
  }

  async function verifyZoomAndFit(canvas = false) {
    await button('Zoom zurücksetzen').click();
    await expect.poll(scale).toBe(1);
    await button('Vergrößern').click();
    await expect.poll(scale).toBeGreaterThan(1);
    await button('Verkleinern').click();
    await expect.poll(scale).toBeCloseTo(1, 5);
    await button('Diagramm einpassen').click();
    const fitted = await container.evaluate((host, isCanvas) => {
      const surface = host.querySelector('svg, canvas');
      const transform = window.d3.zoomTransform(surface);
      const bounds = isCanvas ? { x: 0, y: 0,
        width: surface.width / (window.devicePixelRatio || 1),
        height: surface.height / (window.devicePixelRatio || 1) }
        : surface.querySelector('g').getBBox();
      return { left: transform.x + bounds.x * transform.k,
        top: transform.y + bounds.y * transform.k,
        right: transform.x + (bounds.x + bounds.width) * transform.k,
        bottom: transform.y + (bounds.y + bounds.height) * transform.k,
        availableWidth: host.clientWidth };
    }, canvas);
    // Allow fractional SVG text/hover geometry while requiring a real margin.
    evidence.assert(fitted.left >= 14 && fitted.top >= 14
      && fitted.right <= fitted.availableWidth - 14 && fitted.bottom <= 386,
    'Local Fit must show all graph bounds: ' + JSON.stringify(fitted));
    await button('Zoom zurücksetzen').click();
    await expect.poll(scale).toBe(1);
  }

  for (const [name, method] of [['tree', 'renderTreeDiagram'], ['decision', 'renderDecisionMap']]) {
    await render(method);
    await expect(container).toHaveAttribute('data-transitions-complete', 'true');
    await expect(visibleNodes()).toHaveCount(6);
    await expect(activeNode()).toHaveCount(1);
    const root = container.locator('[data-diagram-node="QA-D"]');
    await root.focus();
    await expect(root).toHaveAttribute('aria-expanded', 'true');
    await page.keyboard.press('Enter');
    await expect(root).toHaveAttribute('aria-expanded', 'false');
    await expect(visibleNodes()).toHaveCount(1);
    await expect(root).toBeFocused();
    await page.keyboard.press('Space');
    await expect(visibleNodes()).toHaveCount(6);
    await expect(container).toHaveAttribute('data-transitions-complete', 'true');
    await page.keyboard.press('ArrowDown');
    await expect(activeNode()).toHaveAttribute('data-diagram-node', 'QA-D-A');
    await expect(activeNode()).toBeFocused();
    await page.keyboard.press('End');
    await expect(activeNode()).not.toHaveAttribute('data-diagram-node', 'QA-D');
    await page.keyboard.press('Home');
    await expect(root).toBeFocused();
    await verifyZoomAndFit();
    await verifySecondRender(method);
    await evidence.axeState('qa-german-' + name + '-keyboard', '#taxonomyTree');
    await evidence.saveRequiredViewportState('qa-german-' + name + '-keyboard', '#taxonomyTree');
    evidence.passed(name + ' supports one node tab stop, arrow navigation, keyboard collapse/expand and local zoom/Fit/reset');
  }

  await render('renderTreeCanvas');
  const canvas = container.locator('canvas');
  await expect(canvas).toHaveAttribute('tabindex', '0');
  await canvas.focus();
  await page.keyboard.press('Home');
  await expect(canvas).toHaveAttribute('data-diagram-node', 'QA-D');
  await page.keyboard.press('ArrowDown');
  await expect(canvas).toHaveAttribute('data-diagram-node', 'QA-D-A');
  await expect(container.locator('.tax-diagram-selection')).toContainText('Original first branch');
  await page.keyboard.press('Enter');
  await expect(canvas).toHaveAttribute('aria-expanded', 'false');
  await page.keyboard.press('Space');
  await expect(canvas).toHaveAttribute('aria-expanded', 'true');
  await verifyZoomAndFit(true);
  await verifySecondRender('renderTreeCanvas', true);
  await canvas.focus();
  await expect(canvas).toHaveAttribute('data-diagram-node', 'QA-D-A');
  await evidence.axeState('qa-german-canvas-keyboard', '#taxonomyTree');
  await evidence.saveRequiredViewportState('qa-german-canvas-keyboard', '#taxonomyTree');
  evidence.passed('canvas retains the active node across focus changes, announces it and supports keyboard collapse/expand and Fit');

  await render('renderSunburst');
  await expect(visibleNodes()).toHaveCount(6);
  await expect(activeNode()).toHaveCount(1);
  const back = button('↩ zurück');
  await expect(back).toBeDisabled();
  await container.locator('[data-diagram-node="QA-D"]').focus();
  await page.keyboard.press('Enter');
  await expect(back).toBeEnabled();
  await expect(activeNode()).toHaveCount(1);
  await expect(activeNode()).toBeFocused();
  await back.click();
  await expect(back).toBeDisabled();
  await container.locator('[data-diagram-node="QA-D-A"]').focus();
  await page.keyboard.press('Space');
  await expect(back).toBeEnabled();
  await expect(visibleNodes()).toHaveCount(3);
  await render('renderSunburst');
  await expect(back).toBeEnabled();
  await expect(visibleNodes()).toHaveCount(3);
  evidence.passed('sunburst retains the focused branch across a second render');
  await button('Gesamte Taxonomie anzeigen').click();
  await expect(back).toBeDisabled();
  await expect(visibleNodes()).toHaveCount(6);
  await evidence.axeState('qa-german-sunburst-keyboard', '#taxonomyTree');
  await evidence.saveRequiredViewportState('qa-german-sunburst-keyboard', '#taxonomyTree');
  evidence.passed('sunburst supports keyboard branch zoom with native Back and full-taxonomy controls');

  // Logical reading context survives view/root changes and automatic renderer changes.
  const treeContext = { analysisKey: 'viewport-regression-one', view: 'tree', treeRoot: 'QA-D' };
  await render('renderTreeDiagram', { context: treeContext });
  const branch = container.locator('[data-diagram-node="QA-D-A"]');
  await branch.focus();
  await page.keyboard.press('Enter');
  await expect(container).toHaveAttribute('data-transitions-complete', 'true');
  await button('Vergrößern').click();
  await panDiagram();
  const treeViewport = await viewport();
  const treeSelection = await container.getAttribute('data-active-node');
  await render('renderTreeCanvas', { context: treeContext });
  await expect(canvas).toHaveAttribute('data-diagram-node', treeSelection);
  await expect.poll(viewport).toEqual(treeViewport);
  await render('renderTreeDiagram', { context: treeContext });
  await expect(branch).toHaveAttribute('aria-expanded', 'false');
  await expect.poll(viewport).toEqual(treeViewport);
  evidence.assert(await container.evaluate(host => !host._canvasRenderer && host._canvasData === null && host._canvasScores === null),
    'Returning to SVG must clear Canvas export metadata');
  await render('renderDecisionMap', { context: { ...treeContext, view: 'decision' } });
  await expect.poll(viewport).toEqual({ x: 0, y: 0, k: 1 });
  await expect(visibleNodes()).toHaveCount(6);
  await render('renderTreeDiagram', { fixture: [fixture[0].children[1]],
    context: { ...treeContext, treeRoot: 'QA-D-B' } });
  await expect.poll(viewport).toEqual({ x: 0, y: 0, k: 1 });
  await expect(visibleNodes()).toHaveCount(2);
  await render('renderTreeDiagram', { context: treeContext });
  await expect(branch).toHaveAttribute('aria-expanded', 'false');
  await expect.poll(viewport).toEqual(treeViewport);
  for (const view of ['list', 'tree']) {
    if (view === 'tree') {
      await render('renderTreeDiagram', { context: treeContext });
      await container.locator('.tax-diagram-relevance input').focus();
    }
    await page.evaluate(context => {
      const host = document.getElementById('taxonomyTree');
      window.TaxonomyViews.prepareDiagramContext(host, context);
      host.replaceChildren(document.createElement('p'));
      if (context.view === 'tree') {
        window.TaxonomyViews.installDiagramRelevanceControl(host);
        window.TaxonomyViews.restoreDiagramControlFocus(host);
      }
    }, { ...treeContext, view });
    if (view === 'tree') { await expect(container.locator('.tax-diagram-relevance input')).toBeFocused(); }
    await render('renderTreeDiagram', { context: treeContext });
    await expect(branch).toHaveAttribute('aria-expanded', 'false');
    await expect.poll(viewport).toEqual(treeViewport);
  }
  await render('renderTreeDiagram', { context: { ...treeContext, analysisKey: 'viewport-regression-two' } });
  await expect(branch).toHaveAttribute('aria-expanded', 'true');
  await expect.poll(viewport).toEqual({ x: 0, y: 0, k: 1 });
  await expect(container.locator('.tax-diagram-relevance input')).not.toBeFocused();
  evidence.passed('tree context survives Canvas/SVG, other views, other roots and empty projections; a new analysis resets it');

  // Growing evidence can change geometry even though the reader did not navigate.
  const grown = structuredClone(fixture);
  grown[0].children[0].children.unshift({ code: 'QA-D-GROWTH', name: 'Additional deterministic presentation fixture', children: [] });
  for (const [method, view] of [['renderTreeDiagram', 'tree'], ['renderDecisionMap', 'decision']]) {
    const context = { analysisKey: 'viewport-growth-' + view, view, treeRoot: 'QA-D' };
    await render(method, { context });
    await expect(container).toHaveAttribute('data-transitions-complete', 'true');
    const selected = container.locator('[data-diagram-node="QA-D-A1"]');
    await selected.focus();
    await button('Diagramm einpassen').click();
    const position = () => selected.evaluate(node => {
      const matrix = node.getScreenCTM();
      return { x: matrix.e, y: matrix.f };
    });
    const before = await position();
    const beforeScale = await scale();
    await render(method, { context, fixture: grown, scores: { ...scores, 'QA-D-GROWTH': 82 } });
    await expect(container).toHaveAttribute('data-transitions-complete', 'true');
    const after = await position();
    evidence.assert(Math.abs(after.x - before.x) < 1 && Math.abs(after.y - before.y) < 1,
      method + ' moved the visible selected node after evidence growth: ' + JSON.stringify({ before, after }));
    await expect.poll(scale).toBe(beforeScale);
  }
  evidence.passed('SVG tree and Decision keep the selected visible node at the same screen position when the layout grows');

  const sunContext = { analysisKey: 'viewport-sun-fallback', view: 'sunburst' };
  await render('renderSunburst', { context: sunContext });
  await container.locator('[data-diagram-node="QA-D-A"]').focus();
  await page.keyboard.press('Enter');
  await expect(visibleNodes()).toHaveCount(3);
  await render('renderSunburst', { context: sunContext,
    fixture: [{ ...fixture[0], children: [fixture[0].children[1]] }] });
  await expect(back).toBeEnabled();
  await expect(activeNode()).toHaveAttribute('data-diagram-node', 'QA-D');
  await expect(visibleNodes()).toHaveCount(3);
  await back.click();
  await expect(back).toBeDisabled();
  await container.locator('[data-diagram-node="QA-D"]').focus();
  await page.keyboard.press('Enter');
  await render('renderSunburst', { context: sunContext, fixture: [fixture[0].children[1]] });
  await expect(back).toBeDisabled();
  await expect(visibleNodes()).toHaveCount(2);
  evidence.passed('sunburst falls back to its surviving ancestor, then the overview, when the focused branch disappears');

  for (const method of ['renderTreeDiagram', 'renderTreeCanvas', 'renderDecisionMap']) {
    const context = { analysisKey: 'viewport-bulk-' + method,
      view: method === 'renderDecisionMap' ? 'decision' : 'tree', treeRoot: 'QA-D' };
    await render(method, { context });
    await container.locator('.tax-diagram-collapse-all').click();
    await render(method, { context });
    if (method === 'renderTreeCanvas') {
      await canvas.focus();
      await page.keyboard.press('End');
      await expect(canvas).toHaveAttribute('data-diagram-node', 'QA-D');
      await expect(canvas).toHaveAttribute('aria-expanded', 'false');
    } else {
      await expect(visibleNodes()).toHaveCount(1);
      await expect(container.locator('[data-diagram-node="QA-D"]')).toHaveAttribute('aria-expanded', 'false');
    }
    await container.locator('.tax-diagram-expand-all').click();
    await render(method, { context });
    if (method === 'renderTreeCanvas') {
      await canvas.focus();
      await page.keyboard.press('End');
      await expect(canvas).toHaveAttribute('data-diagram-node', 'QA-D-B1');
    } else { await expect(visibleNodes()).toHaveCount(6); }
  }
  evidence.passed('SVG tree, Canvas tree and Decision retain Collapse All and Expand All across redraws');

  // Leave the following locale/print scenarios with a fresh rendering context.
  await container.evaluate(host => window.TaxonomyViews.prepareDiagramContext(host, { analysisKey: {}, view: 'decision' }));
}
