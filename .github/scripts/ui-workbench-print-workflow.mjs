import { expect } from '@playwright/test';
import path from 'node:path';

export async function runWorkbenchPrintWorkflow({ page, evidence, outputDir }) {
  const button = page.locator('#printArchitectureView');
  await expect(button).toHaveText('Ansicht drucken');
  await expect(button).toBeEnabled();
  await expect(page.locator('.architecture-print-view')).toBeHidden();
  const details = page.locator('.workbench-detail-panel');
  const requirement = page.locator('#requirementText');
  await expect(details).toHaveAttribute('tabindex', '0');
  await details.focus();
  await expect(details).toBeFocused();
  await requirement.focus();
  const scrollBefore = await requirement.evaluate(element => element.scrollTop);
  await page.keyboard.press('PageDown');
  await expect.poll(() => requirement.evaluate(element => element.scrollTop)).toBeGreaterThan(scrollBefore);
  const outline = await requirement.evaluate(element => getComputedStyle(element).outlineWidth);
  evidence.assert(parseFloat(outline) >= 2, 'The scrollable requirement must have a visible keyboard focus');
  evidence.passed('long workbench requirements are reachable and scrollable by keyboard with a visible focus');
  await page.locator('#zoomArchitectureIn').click();
  await page.locator('#zoomArchitectureIn').click();
  const before = await page.locator('#architectureCanvas').evaluate(svg => ({
    viewBox: svg.getAttribute('viewBox'),
    transform: svg.querySelector('.architecture-viewport').getAttribute('transform'),
    selected: svg.querySelector('.is-selected')?.getAttribute('data-node-id')
  }));
  await page.evaluate(() => {
    window.__qaWorkbenchPrintEvents = 0;
    window.addEventListener('beforeprint', () => { window.__qaWorkbenchPrintEvents++; });
  });
  await button.click();
  await expect.poll(() => page.evaluate(() => window.__qaWorkbenchPrintEvents)).toBeGreaterThan(0);
  await expect(page.locator('#architecturePrintDiagram svg')).toHaveCount(0);
  // Native beforeprint is shared by button, keyboard/browser print and PDF printing.
  await page.evaluate(() => window.dispatchEvent(new Event('beforeprint')));
  await page.emulateMedia({ media: 'print' });
  await expect(page.locator('.architecture-print-view')).toBeVisible();
  const print = await page.locator('#architecturePrintDiagram svg').evaluate(svg => {
    const viewport = svg.querySelector('.architecture-viewport');
    const bounds = viewport.getBBox();
    const viewBox = svg.viewBox.baseVal;
    const requirement = document.getElementById('requirementText');
    return { nodes: svg.querySelectorAll('.architecture-node').length,
      transform: viewport.getAttribute('transform'),
      containsBounds: viewBox.x <= bounds.x && viewBox.y <= bounds.y
        && viewBox.x + viewBox.width >= bounds.x + bounds.width
        && viewBox.y + viewBox.height >= bounds.y + bounds.height,
      text: svg.textContent,
      detailsOverflow: getComputedStyle(document.querySelector('.workbench-detail-panel')).overflow,
      requirementOverflow: getComputedStyle(requirement).overflow,
      requirementMaxHeight: getComputedStyle(requirement).maxHeight,
      requirementEnd: requirement.textContent.includes('QA Requirement row 80'),
      dimmedOpacity: [...svg.querySelectorAll('.architecture-node.is-muted')]
        .map(node => getComputedStyle(node).opacity),
      provenance: document.getElementById('architectureProvenance').textContent
    };
  });
  evidence.assert(print.nodes === 2 && print.transform === null && print.containsBounds,
    'Print must include both complete nodes independently of pan/zoom: ' + JSON.stringify(print));
  evidence.assert(print.text.includes('Original distant architecture title'), 'The distant node is missing from print');
  evidence.assert(print.detailsOverflow === 'visible' && print.requirementOverflow === 'visible'
    && print.requirementMaxHeight === 'none' && print.requirementEnd,
  'Print must release scroll clipping and include the final requirement line');
  evidence.assert(print.dimmedOpacity.every(value => value === '1'), 'Printed context nodes must stay legible');
  evidence.assert(print.provenance.includes('qa-locale') && print.provenance.includes('qa-verifikation'),
    'Printed workbench must retain snapshot and branch provenance');
  if (outputDir && page.context().browser().browserType().name() === 'chromium') {
    await page.pdf({ path: path.join(outputDir, 'workbench-view-print.pdf'),
      format: 'A4', printBackground: true, preferCSSPageSize: true });
  }
  await page.evaluate(() => window.dispatchEvent(new Event('afterprint')));
  await page.emulateMedia({ media: 'screen' });
  await expect(page.locator('.architecture-print-view')).toBeHidden();
  await expect(page.locator('#architecturePrintDiagram svg')).toHaveCount(0);
  await expect.poll(() => page.locator('#architectureCanvas').evaluate(svg => ({
    viewBox: svg.getAttribute('viewBox'),
    transform: svg.querySelector('.architecture-viewport').getAttribute('transform'),
    selected: svg.querySelector('.is-selected')?.getAttribute('data-node-id')
  }))).toEqual(before);
  await evidence.axeState('qa-german-workbench-after-print', '.workbench-grid');
  evidence.passed('native workbench printing includes the entire displayed graph, provenance and long requirement; screen zoom and selection are preserved');
}
