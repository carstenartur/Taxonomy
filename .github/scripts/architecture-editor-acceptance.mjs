import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';

/** Real authenticated UI commands, sharing the existing role/engine/reflow application fixture. */
export async function runArchitectureEditorAcceptance({ page, role, baseUrl, evidence, outputDir, httpFailures }) {
  const failureStart = httpFailures.length;
  const expectedFailures = [];
  const accepted = [];
  const base = baseUrl.replace(/\/$/, '');
  await page.evaluate(async () => {
    if (window.TaxonomyAnalysisSession) await window.TaxonomyAnalysisSession.saveNow();
  });
  await page.waitForLoadState('networkidle');
  const initialResponse = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/api/architecture/editor') && response.request().method() === 'GET');
  await page.goto(`${base}/architecture/editor?lang=en`);
  const initial = await (await initialResponse).json();
  await page.waitForFunction(() => document.getElementById('architectureEditor')?.getAttribute('aria-busy') === 'false');
  assert.ok(initial.document.context.repositoryId);
  assert.ok(initial.document.context.workspaceScopeKey);
  assert.ok(initial.document.context.actor);
  const measurements = { role, initialElements: initial.model.elements.length, commands: [], expectedFailures, reflow: [] };

  async function expectFailure(action, endpoint, status, code) {
    const response = page.waitForResponse(r => new URL(r.url()).pathname.endsWith(endpoint) && r.status() === status);
    await action();
    const rejected = await response;
    const problem = await rejected.json();
    assert.equal(problem.code, code);
    const responseHeaders = rejected.headers();
    const requestHeaders = rejected.request().headers();
    const requestId = responseHeaders['x-request-id'] || responseHeaders['x-correlation-id']
      || requestHeaders['x-request-id'] || requestHeaders['x-correlation-id'];
    assert.ok(requestId, 'Expected rejection must be tied to the exact request');
    expectedFailures.push({ path: new URL(rejected.url()).pathname, status, code,
      requestId });
  }
  async function preview(action) {
    const response = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/api/architecture/editor/preview'));
    await action();
    const result = await response;
    assert.equal(result.status(), 200, await result.text());
    await page.locator('#editorPreviewDialog[open]').waitFor();
    return result.json();
  }
  async function commit() {
    const response = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/api/architecture/editor/commands'));
    await page.waitForFunction(() => document.activeElement?.id === 'editorAccept' && !document.activeElement.disabled);
    await page.keyboard.press('Enter');
    const result = await response;
    assert.ok([200, 202].includes(result.status()), await result.text());
    const value = await result.json();
    assert.equal(value.operationId, value.commandId);
    assert.equal(value.commitCreated, undefined);
    assert.ok(Number.isSafeInteger(value.context.revision));
    await page.waitForFunction(revision => new URL(location.href).searchParams.get('revision') === String(revision)
      && document.getElementById('architectureEditor').getAttribute('aria-busy') === 'false', value.context.revision);
    accepted.push(value.operationId);
    measurements.commands.push({ operationId: value.operationId, revision: value.context.revision, checkpoint: value.context.commit, changedIds: value.change.changedIds });
    return value;
  }
  async function create(type, title, owner = '') {
    await page.locator('#editorNew').click();
    await page.locator('#editorType').selectOption(type);
    await page.locator('#editorTitle').fill(title);
    await page.locator('#editorOwner').fill(owner);
    await preview(() => page.locator('#editorPreviewElement').click());
    const result = await commit();
    return `arch-${result.commandId}`;
  }
  async function select(id) {
    await page.locator('#editorSearch').fill(id);
    await page.locator('#editorTree button').first().click();
    assert.equal(await page.locator('#editorSelection').innerText(), id);
  }
  async function reflow() {
    const viewport = page.viewportSize();
    for (const width of [720, 360]) {
      await page.setViewportSize({ width, height: 800 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 2), true,
        `Editor overflow at ${width}px`);
      const target = page.locator(role === 'USER' ? '#editorRefresh' : '#editorPreviewElement');
      await target.scrollIntoViewIfNeeded();
      await target.focus();
      assert.equal(await target.evaluate(element => {
        const rect = element.getBoundingClientRect();
        return element.contains(document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2));
      }), true, 'Editor control is covered');
      measurements.reflow.push({ width, controlsReachable: true });
    }
    await page.setViewportSize(viewport);
  }

  if (role === 'USER') {
    assert.equal(initial.mayEdit, false);
    assert.equal(await page.locator('#editorNew').isDisabled(), true);
    assert.equal(await page.locator('#editorTitle').isDisabled(), true);
    assert.equal(await page.locator('#editorType').isDisabled(), true);
    await reflow();
  } else {
    if (!initial.mayEdit) {
      await page.locator('#editorWorkspace').click();
      await page.locator('#editorNew').waitFor();
    }
    await page.locator('#editorRationale').fill('Browser acceptance architecture decision');
    await page.locator('#editorTree button').first().click();
    const unchanged = await preview(() => page.locator('#editorPreviewElement').click());
    assert.deepEqual(unchanged.change.changes, [], 'Unchanged imported properties must not gain empty fields or defaults');
    assert.equal(await page.locator('#editorAccept').isDisabled(), true);
    await page.locator('#editorCancel').click();
    const suffix = Date.now().toString(36);
    const system = await create('System', `Architecture system ${suffix}`, 'Architecture owner');
    await page.locator('#editorOwner').fill('');
    const clearing = await preview(() => page.locator('#editorPreviewElement').click());
    assert.ok(clearing.change.changes[0].before.includes('Architecture owner'));
    assert.match(clearing.change.changes[0].after, /x-owner:\s*""/);
    await commit();
    assert.equal(await page.locator('#editorOwner').inputValue(), '');
    const component = await create('Component', `Architecture component ${suffix}`);
    await select(system);
    await page.locator('#editorRelationType').selectOption('RELATED_TO');
    await page.locator('#editorRelationTarget').fill(system);
    await expectFailure(() => page.locator('#editorRelationForm button[type=submit]').click(),
      '/api/architecture/editor/preview', 422, 'SELF_RELATION');
    await page.locator('#editorRelationType').selectOption('CONTAINS');
    await page.locator('#editorRelationTarget').fill(component);
    await preview(() => page.locator('#editorRelationForm button[type=submit]').click());
    await commit();
    await expectFailure(() => page.locator('#editorDelete').click(), '/api/architecture/editor/preview', 409, 'DEPENDENCIES_EXIST');
    await page.locator('#editorTitle').fill(`Updated system ${suffix}`);
    await preview(() => page.locator('#editorPreviewElement').click());
    await commit();
    await preview(() => page.locator('#editorHistory li').first().getByRole('button').click());
    await commit();
    assert.equal(await page.locator('#editorTitle').inputValue(), `Architecture system ${suffix}`);
    await page.reload();
    await page.waitForFunction(() => document.getElementById('architectureEditor')?.getAttribute('aria-busy') === 'false');
    await page.locator('#editorRationale').fill('Redo after browser reload');
    await preview(() => page.locator('#editorHistory li').first().getByRole('button').click());
    await commit();
    assert.equal(await page.locator('#editorTitle').inputValue(), `Updated system ${suffix}`);
    assert.ok((await page.locator('#editorDsl').inputValue()).includes(`Updated system ${suffix}`));
    const deepLink = page.url();
    const currentParams = new URL(deepLink).searchParams;
    currentParams.set('workspaceId', currentParams.get('workspaceScopeKey'));
    const currentResponse = await page.request.get(`${base}/api/architecture/editor?${currentParams}`);
    assert.equal(currentResponse.status(), 200);
    const current = await currentResponse.json();
    assert.equal(current.model.elements.find(element => element.id === system).title, `Updated system ${suffix}`);
    const svg = await page.request.get(new URL(await page.locator('#editorSvg').getAttribute('href'), page.url()).href);
    assert.equal(svg.status(), 200);
    assert.equal(svg.headers().etag, `"workspace-revision-${current.document.context.revision}"`);
    assert.ok((await svg.text()).includes(system));
    const pdf = await page.request.get(new URL(await page.locator('#editorPdf').getAttribute('href'), page.url()).href);
    assert.equal(pdf.status(), 200);
    assert.equal(pdf.headers().etag, svg.headers().etag);

    // A second accepted writer advances the branch after the real UI has previewed its intent.
    await page.locator('#editorTitle').fill(`Reapplied system ${suffix}`);
    await preview(() => page.locator('#editorPreviewElement').click());
    const stale = await page.evaluate(async ({ c, id }) => {
      const uuid = crypto.randomUUID();
      return window.ArchitectureEditorApi.execute({ context: c, kind: 'UPDATE_ELEMENT', id,
        properties: { description: 'Concurrent accepted description' },
        metadata: { commandId: uuid, correlationId: uuid, causationId: uuid, rationale: 'Concurrent writer fixture' } });
    }, { c: current.document.context, id: system });
    assert.ok(stale.operationId);
    assert.equal(stale.context.commit, current.document.context.commit);
    await expectFailure(() => page.locator('#editorAccept').click(), '/api/architecture/editor/commands', 412, 'REVISION_MOVED');
    await page.waitForFunction(() => document.activeElement?.id === 'editorReapply' && !document.activeElement.disabled);
    await preview(() => page.keyboard.press('Enter'));
    assert.ok((await page.locator('#editorChanges').innerText()).includes('Concurrent accepted description'));
    await commit();
    assert.equal(await page.locator('#editorTitle').inputValue(), `Reapplied system ${suffix}`);
    assert.equal(await page.locator('#editorDescription').inputValue(), 'Concurrent accepted description');

    await page.locator('#editorRationale').fill('Explicit removal after dependency preview');
    await preview(() => page.locator('#editorRelations li').first().getByRole('button').last().click());
    await commit();
    await select(component);
    await preview(() => page.locator('#editorDelete').click());
    await commit();
    assert.ok(!(await page.locator('#editorDsl').inputValue()).includes(`element ${component} type`));
    // Two explicit versions with a durable edit between them; both histories survive reload.
    const beforeVersions = await page.locator('#editorVersions li').count();
    const beforeOperations = await page.locator('#editorHistory li').count();
    async function checkpoint() {
      const response = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/api/architecture/editor/checkpoints'));
      await page.locator('#editorCheckpoint').click();
      const result = await response; assert.equal(result.status(), 200, await result.text());
      const value = await result.json(); assert.equal(value.commitCreated, true);
      await page.waitForFunction(() => document.getElementById('architectureEditor').getAttribute('aria-busy') === 'false'
        && !document.getElementById('editorCheckpoint').disabled);
      return value;
    }
    const firstCheckpoint = await checkpoint();
    assert.equal(await page.locator('#editorHistory li').count(), beforeOperations);
    assert.equal(await page.locator('#editorVersions li').count(), beforeVersions + 1);
    await select(system); await page.locator('#editorTitle').fill(`After checkpoint ${suffix}`);
    await preview(() => page.locator('#editorPreviewElement').click());
    const afterCheckpoint = await commit();
    assert.equal(afterCheckpoint.context.commit, firstCheckpoint.commitId);
    const secondCheckpoint = await checkpoint();
    assert.notEqual(secondCheckpoint.commitId, firstCheckpoint.commitId);
    await page.reload();
    await page.waitForFunction(() => document.getElementById('architectureEditor')?.getAttribute('aria-busy') === 'false');
    assert.equal(await page.locator('#editorVersions li').count(), beforeVersions + 2);
    assert.equal(await page.locator('#editorHistory li').count(), beforeOperations + 1);
    assert.ok((await page.locator('#editorDsl').inputValue()).includes(`After checkpoint ${suffix}`));
    measurements.separateOperationAndVersionHistory = true;
    await reflow();
    measurements.deepLinkRestored = true;
    measurements.undoRedoAfterReload = true;
    measurements.staleReappliedExplicitly = true;
    measurements.exportRevisionParity = true;
    measurements.unchangedFormIsNoOp = true;
    measurements.explicitPropertyClear = true;
  }
  // Bounded renderer spike: neutral DiagramScene fixtures, never replacement taxonomy/domain state.
  measurements.renderer = await page.evaluate(async () => {
    const measurements = [];
    const settle = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    for (const count of [309, 1000, 5000]) {
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('viewBox', '0 0 1000 420');
      svg.setAttribute('aria-hidden', 'true');
      svg.style.cssText = 'position:absolute;left:-2000px;width:1000px;height:420px';
      document.body.append(svg);
      const scene = { width: Math.ceil(count / 5) * 262 + 104, height: 684, edges: [], nodes: [] };
      for (let i = 0; i < count; i++) scene.nodes.push({ id: `scene-fixture-${i}`, label: `Scene ${i}`, type: 'System',
        x: 52 + Math.floor(i / 5) * 262, y: 52 + (i % 5) * 116, width: 238, height: 82 });
      for (let i = 1; i < count; i++) {
        const source = scene.nodes[i - 1], target = scene.nodes[i];
        scene.edges.push({ id: `scene-edge-${i}`, sourceId: source.id, targetId: target.id, relationType: 'RELATED_TO',
          sourceX: source.x + source.width, sourceY: source.y + 41, targetX: target.x, targetY: target.y + 41 });
      }
      const started = performance.now();
      const adapter = window.ArchitectureEditorRenderer(svg, () => {}, () => {}, () => {});
      try {
        adapter.render(scene, null); adapter.fit(); await settle();
        const renderMs = Math.round(performance.now() - started);
        const visibleNodes = svg.querySelectorAll('.editor-node').length;
        const focusStarted = performance.now();
        adapter.select(scene.nodes[count - 1].id); adapter.focus(); await settle();
        const focusMs = Math.round(performance.now() - focusStarted);
        measurements.push({ count, renderMs, focusMs, visibleNodes, domElements: svg.querySelectorAll('*').length,
          selectedVisible: Boolean(svg.querySelector('.editor-node[aria-pressed="true"]')) });
      } finally { adapter.destroy(); svg.remove(); }
    }
    return measurements;
  });
  for (const measurement of measurements.renderer) {
    assert.ok(measurement.visibleNodes <= 200 && measurement.domElements <= 1100);
    assert.equal(measurement.selectedVisible, true);
    assert.ok(measurement.renderMs < 1500 && measurement.focusMs < 1500,
      `Renderer exceeded its 1500 ms CI budget: ${JSON.stringify(measurement)}`);
  }
  await evidence.runAxe('architecture-editor');
  assert.ok(await page.locator('#editorGraph .editor-node').count() <= 200);
  assert.ok(await page.locator('#editorTree button').count() <= 50);
  for (const failure of httpFailures.slice(failureStart)) {
    assert.ok(expectedFailures.some(expected => expected.requestId && expected.requestId === failure.requestId
      && expected.path === failure.path && expected.status === failure.status), `Unexpected editor HTTP failure: ${JSON.stringify(failure)}`);
  }
  await page.locator('#architectureEditor').screenshot({ path: path.join(outputDir, 'architecture-editor.png'), animations: 'disabled' });
  await writeFile(path.join(outputDir, 'architecture-editor.json'), `${JSON.stringify(measurements, null, 2)}\n`);
  return measurements;
}
