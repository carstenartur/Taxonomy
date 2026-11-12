import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import path from 'node:path';

const fixtureUrl = new URL('../../taxonomy-app/src/test/resources/ui/seven-layer-impact-map.json', import.meta.url);
const budgetsUrl = new URL('../large-result-budget.json', import.meta.url);

// Exercise the live result first. Reconstruct the historical size in an isolated
// tab, keeping the active analysis and the report's frozen source intact.
export async function measureImpactMapReading(page, outputDir) {
  const actual = await inspectGraph(page.locator('#impactGraphView'), outputDir, 'hospital-desktop');
  const fixture = JSON.parse(await readFile(fixtureUrl, 'utf8'));
  const {responsiveProfiles} = JSON.parse(await readFile(budgetsUrl, 'utf8'));
  const sample = await page.context().newPage();
  try {
    // Load the production diagram and styles, then mount only the measured scene.
    await sample.goto(page.url(), {waitUntil: 'domcontentloaded'});
    await sample.waitForFunction(() => window.TaxonomyImpactMap && window.d3);
    await sample.evaluate(() => {
      const stage = document.createElement('article');
      stage.id = 'historicalImpactFixture';
      stage.style.cssText = 'width: min(100%, 781px); margin-inline: auto';
      document.body.replaceChildren(stage);
    });
    const session = await sample.context().newCDPSession(sample);
    const profiles = [{id: 'desktop', cssWidth: 1440, cssHeight: 1000,
      deviceScaleFactor: 1, mobile: false}, ...responsiveProfiles];
    const results = [];
    for (const profile of profiles) {
      await session.send('Emulation.setDeviceMetricsOverride', {
        width: profile.cssWidth, height: profile.cssHeight,
        deviceScaleFactor: profile.deviceScaleFactor, mobile: profile.mobile
      });
      const root = sample.locator('#historicalImpactFixture');
      await root.evaluate((host, {nodes, edges}) => {
        window.TaxonomyImpactMap.render(host, nodes, edges, {});
      }, fixture);
      const result = await inspectGraph(root, outputDir, 'seven-layer-' + profile.id);
      assert.deepEqual(result.shape, {elements: 20, relationships: 16, layers: 7});
      results.push({profile, ...result});
    }
    return {actualHospital: actual, reconstruction: {sourceCommit: fixture.sourceCommit,
      description: fixture.description, profiles: results}};
  } finally {
    await sample.close();
  }
}

async function inspectGraph(root, outputDir, id) {
  await root.locator('[data-fit-state="complete"]').waitFor();
  const graph = await root.evaluate(host => {
    const diagram = host.__taxonomyImpactMapDiagram;
    const model = diagram.getModel();
    const related = new Set(model.edges.flatMap(edge => [edge.source, edge.target]));
    const select = host.querySelector('.impact-map-inspect-select');
    const index = [...select.options].findIndex(option => related.has(option.value));
    const glyph = host.querySelector('.impact-map-node-title');
    const transform = glyph.getScreenCTM();
    const card = host.querySelector('.impact-map-node-card').getBoundingClientRect();
    return {shape: {elements: model.nodes.length, relationships: model.edges.length, layers: model.layers.length},
      index, selectedCode: select.options[index]?.value, viewport: diagram.captureViewport(),
      fittedGlyphPx: parseFloat(getComputedStyle(glyph).fontSize) * Math.hypot(transform.a, transform.b),
      fittedCardWidthPx: card.width, fittedCardHeightPx: card.height,
      selectorHeightPx: select.getBoundingClientRect().height,
      selectorFontPx: parseFloat(getComputedStyle(select).fontSize)};
  });
  assert.ok(graph.index > 0, 'At least one connected, inspectable element is required');
  const select = root.locator('.impact-map-inspect-select');
  await select.focus();
  await select.press('Home');
  for (let i = 0; i < graph.index; i++) await select.press('ArrowDown');
  assert.equal(await select.inputValue(), graph.selectedCode);
  assert.equal(await root.locator('.impact-map-detail-code').innerText(), graph.selectedCode);
  const score = (await root.locator('.impact-map-detail-grid dd').allTextContents())
    .find(value => /^\d+%$/.test(value));
  assert.ok(score);
  const relation = root.locator('.impact-map-related-item').first();
  const relationship = await relation.innerText();
  const targetHeightPx = (await relation.boundingBox()).height;
  const detailFontPx = await root.locator('.impact-map-detail-title')
    .evaluate(element => parseFloat(getComputedStyle(element).fontSize));
  const screenshot = 'impact-map-' + id + '.png';
  await root.screenshot({path: path.join(outputDir, screenshot)});
  await relation.press('Enter');
  const relatedCode = await select.inputValue();
  assert.ok(relatedCode && relatedCode !== graph.selectedCode && relationship.includes(relatedCode));
  assert.equal(await root.locator('.impact-map-detail-code').innerText(), relatedCode);
  const after = await root.evaluate(host => ({viewport: host.__taxonomyImpactMapDiagram.captureViewport(),
    mode: host.__taxonomyImpactMapDiagram.getState().mode,
    focusRestored: document.activeElement === host.querySelector('.impact-map-inspect-select')}));
  assert.deepEqual(after.viewport, graph.viewport, 'Reading a relationship changed the fitted overview');
  assert.equal(after.mode, 'overview');
  assert.ok(after.focusRestored);
  assert.ok(graph.selectorHeightPx >= 44 && graph.selectorFontPx >= 16
    && detailFontPx >= 16 && targetHeightPx >= 24, 'Native reading controls fall below the reading budgets');
  return {...graph, score, relationship, relatedCode, targetHeightPx, detailFontPx, screenshot};
}
