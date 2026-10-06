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
  async function render(method) {
    await page.evaluate(({ method, fixture, scores }) => {
      window.TaxonomyViews[method](document.getElementById('taxonomyTree'), fixture, scores);
    }, { method, fixture, scores });
  }
  const visibleNodes = () => container.locator('[data-diagram-node]:not([aria-hidden="true"])');
  const activeNode = () => container.locator('[data-diagram-node][tabindex="0"]');
  const button = name => container.locator('.tax-diagram-controls').getByRole('button', { name, exact: true });
  const scale = () => container.locator('svg, canvas').first().evaluate(surface => window.d3.zoomTransform(surface).k);

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
  await button('Gesamte Taxonomie anzeigen').click();
  await expect(back).toBeDisabled();
  await expect(visibleNodes()).toHaveCount(6);
  await evidence.axeState('qa-german-sunburst-keyboard', '#taxonomyTree');
  await evidence.saveRequiredViewportState('qa-german-sunburst-keyboard', '#taxonomyTree');
  evidence.passed('sunburst supports keyboard branch zoom with native Back and full-taxonomy controls');
}
