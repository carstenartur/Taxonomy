import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { csrfJson } from './ui-role-fixtures.mjs';

export async function measureEditorFrame(page, selected = false) {
  return page.locator('#editorGraph').evaluate(async (svg, selected) => {
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const node = svg.querySelector(selected ? '.editor-node[aria-pressed="true"]' : '.editor-node');
    const box = element => {
      const rect = element.getBoundingClientRect();
      return { x: rect.x, y: rect.y, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height };
    };
    return { viewport: box(svg), node: node && box(node.querySelector('rect')),
      label: node && box(node.querySelector('text')), nodes: svg.querySelectorAll('.editor-node').length,
      edges: svg.querySelectorAll('.editor-edge').length };
  }, selected);
}

export function assertReadableEditorFrame(frame, description) {
  assert.ok(frame.node && frame.node.width >= 180 && frame.node.height >= 50,
    `${description}: the first framed node must have readable physical size: ${JSON.stringify(frame)}`);
  assert.ok(frame.label.height >= 10, `${description}: the label must remain legible`);
  assert.ok(frame.node.x >= frame.viewport.x - 1 && frame.node.y >= frame.viewport.y - 1
    && frame.node.right <= frame.viewport.right + 1 && frame.node.bottom <= frame.viewport.bottom + 1,
  `${description}: the framed node must be fully inside the graph`);
  assert.ok(frame.nodes <= 200 && frame.edges <= 400, 'Framing must retain rendering budgets');
}

export async function verifyConstrainedEditorFrames(page) {
  const frames = await page.evaluate(async () => {
    const frames = [];
    for (const width of [0, 30]) {
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('viewBox', '0 0 1000 420');
      svg.style.cssText = `position:absolute;left:-2000px;width:${width}px;height:420px`;
      document.body.append(svg);
      const adapter = window.ArchitectureEditorRenderer(svg, () => {}, () => {}, () => {});
      try {
        adapter.render({ width: 760, height: 420, edges: [], nodes: [
          { id: 'viewport-fixture', label: 'Viewport fixture', x: 52, y: 52, width: 238, height: 82 }
        ] }, 'viewport-fixture');
        adapter.fit({ initial: true });
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        const initialScale = window.d3.zoomTransform(svg).k;
        adapter.focus();
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        frames.push({ width, initialScale, focusScale: window.d3.zoomTransform(svg).k,
          rendered: svg.querySelectorAll('.editor-node').length });
      } finally { adapter.destroy(); svg.remove(); }
    }
    return frames;
  });
  for (const frame of frames) {
    assert.ok(Number.isFinite(frame.initialScale) && frame.initialScale >= 0.01 && frame.initialScale <= 4,
      `Initial framing must keep a valid scale in a constrained viewport: ${JSON.stringify(frame)}`);
    assert.ok(Number.isFinite(frame.focusScale) && frame.focusScale >= 0.01 && frame.focusScale <= 4,
      `Selection focus must keep a valid scale in a constrained viewport: ${JSON.stringify(frame)}`);
    assert.equal(frame.rendered, 1, 'A constrained viewport must not invert culling and lose the selected node');
  }
  return frames;
}

/** Real retained central evidence and the existing private journal, including uncertain transport. */
async function runSnapshotAdoptionAcceptance(page, base, initial) {
  const repository = initial.document.context.repositoryId;
  const current = new URL(page.url()).searchParams;
  const branch = current.get('branch') || initial.document.context.branch;
  const sourceWorkspace = current.get('workspaceId');
  assert.ok(sourceWorkspace,'Ordinary analysis fixtures must be created in an isolated source workspace');
  const pin = new URLSearchParams({repositoryId:repository,workspaceId:sourceWorkspace,branch});
  const scoped = path => path + '?' + pin;
  await page.goto(base + '/projects?' + pin + '&lang=en');
  await page.locator('#portfolioMain').waitFor();
  async function request(path,body,method='POST') {
    const response = await csrfJson(page,scoped(path),{method,body});
    assert.ok(response.status >= 200 && response.status < 300, `${path}: ${response.status} ${response.text}`);
    return response.json;
  }
  const suffix = Date.now().toString(36).toUpperCase();
  const project = await request('/api/projects',{projectKey:'ADOPT-'+suffix,title:'Frozen snapshot adoption',description:'Browser acceptance',status:'ACTIVE'});
  const requirementBody = {requirementKey:'REQ-ADOPT',title:'Secure command exchange',
    text:'Provide secure resilient command information exchange with auditable decisions.',status:'APPROVED',priority:90,
    criticality:'MISSION_CRITICAL',requirementType:'SECURITY',reviewStatus:'CONFIRMED',changeReason:'Browser acceptance fixture'};
  const requirement = await request(`/api/projects/${project.id}/requirements`,requirementBody);
  const job = await request(`/api/projects/${project.id}/requirements/${requirement.id}/analyses`,{provider:'GEMINI',maxArchitectureNodes:20,idempotencyKey:'adoption-'+suffix});
  let completed;
  const deadline = Date.now()+120000;
  while (Date.now()<deadline) {
    const current = await request(`/api/projects/${project.id}/analysis-jobs/${job.id}`,undefined,'GET');
    if (['SUCCESS','PARTIAL'].includes(current.status)) { completed=current; break; }
    assert.ok(!['FAILED','CANCELLED'].includes(current.status),JSON.stringify(current));
    await page.waitForTimeout(250);
  }
  assert.ok(completed,'Mock analysis must persist a snapshot within the existing analysis timeout');
  const snapshot = completed.items[0].snapshotId;
  await request(`/api/projects/${project.id}/requirements/${requirement.id}/versions`,{text:'Later edited requirement must not replace frozen evidence.',changeReason:'Retained version regression'});
  await page.goto(base+`/architecture/workbench?projectId=${project.id}&snapshotId=${snapshot}&${pin}&lang=en`);
  await page.locator('#workflow-adopt').waitFor({state:'visible'});
  const sourceUrl = await page.locator('#workflow-adopt').getAttribute('href');
  assert.equal(new URL(sourceUrl,base).searchParams.get('sourceRepositoryId'),repository);
  assert.equal(new URL(sourceUrl,base).searchParams.get('sourceWorkspaceScopeKey'),sourceWorkspace);
  // Select a different editable destination through an explicit central context; the frozen source stays private.
  const destination = new URL(sourceUrl,base); destination.searchParams.set('workspaceId',''); destination.searchParams.set('workspaceScopeKey','__shared__');
  await page.goto(destination.href);
  await page.waitForFunction(()=>document.getElementById('architectureEditor')?.getAttribute('aria-busy')==='false');
  assert.equal(await page.locator('#adoptionPreview').isDisabled(),true,'Opening the central source cannot adopt');
  const copyResponse = page.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/api/architecture/editor/workspaces'));
  await page.locator('#editorWorkspace').click();
  const copy = await (await copyResponse).json();
  await page.waitForFunction(()=>!document.getElementById('adoptionPreview').disabled);
  assert.equal(new URL(page.url()).searchParams.get('sourceWorkspaceScopeKey'),sourceWorkspace);
  assert.equal(new URL(page.url()).searchParams.get('workspaceId'),copy.workspaceId);
  const previewResponse = page.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/snapshot-adoptions/preview'));
  await page.locator('#adoptionPreview').click();
  const previewHttp = await previewResponse; assert.equal(previewHttp.status(),200,await previewHttp.text());
  const preview = await previewHttp.json();
  assert.equal(preview.preview.context.revision,0);
  assert.match(preview.preview.change.dsl,/Provide secure resilient command information exchange/);
  assert.ok(!preview.preview.change.dsl.includes('Later edited requirement'));
  assert.equal(await page.locator('#editorHistory li').count(),0,'Preview must not initialize history');
  await page.waitForFunction(()=>document.activeElement?.id==='adoptionAccept'&&!document.activeElement.disabled);
  await page.evaluate(()=>{
    const original=window.ArchitectureEditorApi.adoptSnapshot;
    window.__snapshotRetry={original,attempts:[]};
    window.ArchitectureEditorApi.adoptSnapshot=async request=>{
      window.__snapshotRetry.attempts.push(JSON.parse(JSON.stringify(request)));
      const result=await original(request);
      if(window.__snapshotRetry.attempts.length===1)throw Object.assign(new Error('Lost accepted snapshot response fixture'),{status:0});
      return result;
    };
  });
  await page.keyboard.press('Enter');
  await page.locator('#editorError').filter({hasText:'Lost accepted snapshot response fixture'}).waitFor();
  const acceptedResponse = page.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/snapshot-adoptions'));
  await page.locator('#adoptionAccept').click();
  const acceptedHttp=await acceptedResponse;assert.equal(acceptedHttp.status(),200,await acceptedHttp.text());
  const adopted=await acceptedHttp.json();assert.equal(adopted.accepted.replayed,true);assert.equal(adopted.accepted.context.revision,1);
  const attempts=await page.evaluate(()=>{const state=window.__snapshotRetry;window.ArchitectureEditorApi.adoptSnapshot=state.original;delete window.__snapshotRetry;return state.attempts;});
  assert.deepEqual(attempts[0],attempts[1]);
  await page.waitForFunction(()=>new URL(location.href).searchParams.get('revision')==='1'&&document.getElementById('architectureEditor').getAttribute('aria-busy')==='false');
  assert.equal(new URL(page.url()).searchParams.get('viewId'),adopted.viewId);
  assert.equal(await page.locator('#editorHistory li').count(),1);
  await page.locator('#snapshotAdoption details > summary').click();
  assert.match(await page.locator('#adoptionProvenance').innerText(),new RegExp(snapshot));
  const returnUrl = new URL(await page.locator('#adoptionSourceLink').getAttribute('href'),base);
  assert.equal(returnUrl.searchParams.get('workspaceId'),sourceWorkspace);assert.equal(returnUrl.searchParams.get('repositoryId'),repository);
  await page.locator('#editorRationale').fill('Retain frozen adoption checkpoint');
  const checkpointResponse=page.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/editor/checkpoints'));
  await page.locator('#editorCheckpoint').click();const checkpoint=await (await checkpointResponse).json();
  await page.waitForFunction(()=>document.getElementById('architectureEditor').getAttribute('aria-busy')==='false');
  // A checkpoint made before adoption has no adopted view. Its history link must open a usable full model.
  const beforeCommit = preview.preview.context.commit;
  assert.ok(beforeCommit);
  await page.locator('#editorVersions a').filter({hasText:beforeCommit.substring(0,12)}).click();
  await page.waitForFunction(()=>document.getElementById('architectureEditor')?.getAttribute('aria-busy')==='false');
  assert.equal(new URL(page.url()).searchParams.has('viewId'),false);
  assert.equal(await page.locator('#editorError').isVisible(),false);
  assert.equal(await page.locator('#editorRefresh').isEnabled(),true);
  await page.locator('#editorRefresh').click();
  await page.waitForFunction(()=>new URL(location.href).searchParams.get('revision')==='1'&&document.getElementById('architectureEditor').getAttribute('aria-busy')==='false');
  await page.locator('#adoptionView').selectOption(adopted.viewId);
  await page.waitForFunction(id=>new URL(location.href).searchParams.get('viewId')===id,adopted.viewId);
  async function invert(kind) {
    const preview=page.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/editor/preview'));
    await page.locator('#editorHistory li').filter({hasText:kind}).first().locator('button').click();
    assert.equal((await preview).status(),200);
    await page.waitForFunction(()=>document.activeElement?.id==='editorAccept'&&!document.activeElement.disabled);
    const accepted=page.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/editor/commands'));
    await page.keyboard.press('Enter');const value=await (await accepted).json();
    await page.waitForFunction(revision=>new URL(location.href).searchParams.get('revision')===String(revision)&&document.getElementById('architectureEditor').getAttribute('aria-busy')==='false',value.context.revision);
    return value;
  }
  const undone=await invert('AdoptAnalysisSnapshot');
  assert.equal(new URL(page.url()).searchParams.has('viewId'),false);
  const redone=await invert('UNDO');
  assert.match(await page.locator('#editorDsl').inputValue(),new RegExp(adopted.viewId));
  await page.locator('#adoptionView').selectOption(adopted.viewId);
  await page.waitForFunction(id=>new URL(location.href).searchParams.get('viewId')===id,adopted.viewId);
  await page.locator('#adoptionSourceLink').click();
  await page.locator('#workflow-adopt').waitFor({state:'visible'});
  assert.match(await page.locator('#requirementText').innerText(),/Provide secure resilient command information exchange/);
  return {snapshot,sourceRepository:repository,targetWorkspace:copy.workspaceId,viewId:adopted.viewId,acceptedRevision:1,
    replayed:true,checkpoint:checkpoint.commitId,undo:undone.operationId,redo:redone.operationId,retainedOldRequirement:true,sourceReturn:true};
}

/** Real authenticated UI commands, sharing the existing role/engine/reflow application fixture. */
export async function runArchitectureEditorAcceptance({ page, role, baseUrl, evidence, outputDir, httpFailures }) {
  const failureStart = httpFailures.length;
  const expectedFailures = [];
  const accepted = [];
  let lastPreviewMetadata;
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
  if (initial.scene.nodes.length) {
    measurements.initialFrame = await measureEditorFrame(page);
    assertReadableEditorFrame(measurements.initialFrame, 'Initial catalogue graph');
  }
  measurements.constrainedFrames = await verifyConstrainedEditorFrames(page);

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
    lastPreviewMetadata = result.request().postDataJSON().metadata;
    if (lastPreviewMetadata.causationId === lastPreviewMetadata.commandId) {
      assert.equal(lastPreviewMetadata.correlationId, lastPreviewMetadata.commandId, 'New intents start their own correlation');
    }
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

    // Give the authored pair a deterministic lexical lead over unrelated catalogue elements.
    const focusDescription = `focus${suffix}alpha focus${suffix}beta focus${suffix}gamma`;
    for (const id of [system, component]) {
      await select(id);
      await page.locator('#editorDescription').fill(focusDescription);
      await preview(() => page.locator('#editorPreviewElement').click());
      await commit();
    }
    await select(system);

    // Real focus endpoint + semantic journal: explicit optimization, local discovery, one-step acceptance and exact UUID undo.
    const arrangeResponse = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/api/architecture/editor')
      && new URL(r.url()).searchParams.get('layout') === 'reduce-crossings');
    await page.locator('#editorLayout').selectOption('reduce-crossings');
    await page.locator('#editorArrange').click();
    const arranged = await (await arrangeResponse).json();
    assert.equal(arranged.schema.layoutStrategy, 'reduce-crossings');
    assert.equal(arranged.schema.layoutRevision, arranged.document.context.revision);
    await page.waitForFunction(() => !document.getElementById('editorLensEnabled').disabled);
    await page.locator('#editorFocus').click();
    await page.locator('#editorLensUseAi').uncheck();
    await page.locator('#editorLensEnabled').check();
    const investigationResponse = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/api/architecture/editor/focus/suggestions'));
    await page.locator('#editorLensInspect').focus(); await page.keyboard.press('Enter');
    const investigated = await investigationResponse;
    assert.equal(investigated.status(), 200, await investigated.text());
    const investigation = await investigated.json();
    assert.equal(investigation.outcome, 'LOCAL_CANDIDATES'); assert.equal(investigation.modelCalls, 0);
    const proposal = investigation.suggestions.find(s => [s.sourceId, s.targetId].includes(component));
    assert.ok(proposal, 'A newly created editor element outside the selected focus is an eligible target');
    const proposalButton = page.locator('#editorLensSuggestions button').filter({ hasText: `${proposal.sourceId} → ${proposal.targetId} · ${proposal.relationType}` });
    await proposalButton.focus(); await page.keyboard.press('Enter');
    assert.equal(await proposalButton.evaluate(button => document.activeElement === button), true, 'Selecting a proposal retains keyboard focus');
    assert.ok((await page.locator('#editorLensReview').innerText()).includes(proposal.rationale));
    assert.equal(await page.locator('#editorPreviewDialog').isVisible(), false);
    const frameBeforeFocusAccept = await page.locator('#editorGraph .editor-node[aria-pressed="true"]').evaluate(node => ({
      node: node.getAttribute('transform'), camera: node.parentElement.getAttribute('transform')
    }));
    // Lose the response AFTER the real command commits, then retry the same command through the real endpoint.
    let focusCommit, focusAttempts;
    try {
      await page.evaluate(() => {
        const original = window.ArchitectureEditorApi.execute;
        window.__focusRetry = { original, attempts: [] };
        window.ArchitectureEditorApi.execute = async command => {
          if (command.kind !== 'CREATE_RELATION' || command.properties?.provenance !== 'architecture-focus') return original(command);
          window.__focusRetry.attempts.push(JSON.parse(JSON.stringify(command)));
          const result = await original(command);
          if (window.__focusRetry.attempts.length === 1) throw Object.assign(new Error('Lost accepted response fixture'), { status: 0 });
          return result;
        };
      });
      await page.locator('#editorLensReview button[data-focus-action="accept"]').focus(); await page.keyboard.press('Enter');
      await page.getByRole('button', { name: 'Retry the same acceptance', exact: true }).waitFor();
      const focusCommitResponse = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/api/architecture/editor/commands'));
      await page.getByRole('button', { name: 'Retry the same acceptance', exact: true }).click();
      focusCommit = await (await focusCommitResponse).json();
      assert.equal(focusCommit.replayed, true);
      await page.waitForFunction(revision => new URL(location.href).searchParams.get('revision') === String(revision)
        && !document.getElementById('editorLensEnabled').disabled, focusCommit.context.revision);
    } finally {
      focusAttempts = await page.evaluate(() => {
        const recovery = window.__focusRetry;
        if (!recovery) return [];
        window.ArchitectureEditorApi.execute = recovery.original;
        delete window.__focusRetry;
        return recovery.attempts;
      });
    }
    assert.deepEqual(focusAttempts[0], focusAttempts[1], 'Ambiguous focus acceptance retries the exact operation UUID and evidence');
    assert.ok((await page.locator('#editorDsl').inputValue()).includes('provenance: "architecture-focus"'));
    assert.deepEqual(await page.locator('#editorGraph .editor-node[aria-pressed="true"]').evaluate(node => ({
      node: node.getAttribute('transform'), camera: node.parentElement.getAttribute('transform')
    })), frameBeforeFocusAccept, 'Acceptance must preserve optimized node positions and the camera');
    assert.equal(await page.locator('#editorPreviewDialog').isVisible(), false, 'Focus review is the single confirmation');
    const focusUndoResponse = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/api/architecture/editor/commands'));
    await page.locator('#editorLensReview button[data-focus-action="undo"]').click();
    const focusUndo = await (await focusUndoResponse).json();
    await page.waitForFunction(revision => new URL(location.href).searchParams.get('revision') === String(revision)
      && !document.getElementById('editorLensEnabled').disabled, focusUndo.context.revision);
    assert.equal(focusUndo.change.dsl.includes(`relation ${proposal.sourceId} ${proposal.relationType} ${proposal.targetId}`), false);
    await page.locator('#editorLensEnabled').uncheck();
    measurements.focus = { localOnly: true, outsideFocusElement: component, acceptance: focusCommit.operationId,
      undo: focusUndo.operationId, retryReplayed: true, stableCameraAndLayout: true, keyboardReview: true };
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
    const staleMetadata = lastPreviewMetadata;
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
    assert.notEqual(lastPreviewMetadata.commandId, staleMetadata.commandId);
    assert.equal(lastPreviewMetadata.correlationId, staleMetadata.correlationId);
    assert.equal(lastPreviewMetadata.causationId, staleMetadata.commandId);
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
    // Inject known/ambiguous application-boundary failures, then complete the real HTTP checkpoint.
    await page.evaluate(() => {
      const original = window.ArchitectureEditorApi.checkpoint;
      const attempts = [];
      window.__editorCheckpointRecovery = { original, attempts };
      window.ArchitectureEditorApi.checkpoint = async command => {
        attempts.push(JSON.parse(JSON.stringify(command)));
        const failure = [{ status: 409, code: 'CHECKPOINT_CONFLICT' },
          { status: 422, code: 'INVALID_VALUE' }, { status: 503, code: 'GIT_UNAVAILABLE' }][attempts.length - 1];
        if (failure) throw Object.assign(new Error('Checkpoint recovery acceptance'),
          { status: failure.status, responseBody: { code: failure.code } });
        return original(command);
      };
    });
    for (let attempt = 1; attempt <= 3; attempt++) {
      await page.locator('#editorRationale').fill(`Checkpoint recovery attempt ${attempt}`);
      await page.locator('#editorCheckpoint').click();
      await page.waitForFunction(count => window.__editorCheckpointRecovery.attempts.length === count
        && !document.getElementById('editorCheckpoint').disabled, attempt);
    }
    const firstCheckpoint = await checkpoint();
    const checkpointAttempts = await page.evaluate(() => {
      const recovery = window.__editorCheckpointRecovery;
      window.ArchitectureEditorApi.checkpoint = recovery.original;
      delete window.__editorCheckpointRecovery;
      return recovery.attempts;
    });
    assert.notEqual(checkpointAttempts[0].metadata.commandId, checkpointAttempts[1].metadata.commandId);
    assert.notEqual(checkpointAttempts[1].metadata.commandId, checkpointAttempts[2].metadata.commandId);
    assert.deepEqual(checkpointAttempts[2], checkpointAttempts[3], 'Ambiguous failures must retry the exact checkpoint');
    measurements.checkpointRejectionAndRetryRecovery = true;
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
    for (const count of [2, 309, 1000, 5000]) for (const width of [300, 1000]) {
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('viewBox', '0 0 1000 420');
      svg.setAttribute('tabindex', '0');
      svg.setAttribute('aria-hidden', 'true');
      svg.style.cssText = `position:absolute;left:-2000px;width:${width}px;height:420px`;
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
        const originalScene = JSON.stringify(scene);
        adapter.render(scene, null); adapter.fit({ initial: true }); await settle();
        const initialRect = svg.querySelector('.editor-node rect').getBoundingClientRect();
        const initialLabel = svg.querySelector('.editor-node text').getBoundingClientRect();
        const graphRect = svg.getBoundingClientRect();
        const initialFrame = { viewport: { x: graphRect.x, y: graphRect.y, right: graphRect.right, bottom: graphRect.bottom },
          node: { x: initialRect.x, y: initialRect.y, right: initialRect.right, bottom: initialRect.bottom,
            width: initialRect.width, height: initialRect.height }, label: { height: initialLabel.height },
          nodes: svg.querySelectorAll('.editor-node').length, edges: svg.querySelectorAll('.editor-edge').length };
        adapter.fit(); await settle();
        adapter.presentFocus({ enabled: true, states: {}, label: value => value,
          suggestions: scene.edges.slice(0, 64).map(edge => ({ ...edge, id: `proposal-${edge.id}` })) });
        const fitTransform = window.d3.zoomTransform(svg);
        const completeModelFits = fitTransform.x >= 0 && fitTransform.y >= 0
          && fitTransform.x + scene.width * fitTransform.k <= 1000
          && fitTransform.y + scene.height * fitTransform.k <= 420;
        const renderMs = Math.round(performance.now() - started);
        const visibleNodes = svg.querySelectorAll('.editor-node').length;
        const visibleEdges = svg.querySelectorAll('.editor-edge, .editor-ghost').length;
        const visibleSuggestions = svg.querySelectorAll('.editor-ghost').length;
        const markerId = svg.querySelector('marker').id;
        const markerIsUnique = document.querySelectorAll('#' + markerId).length === 1;
        const edgesUseOwnMarker = [...svg.querySelectorAll('.editor-edge, .editor-ghost')]
          .every(edge => edge.getAttribute('marker-end') === 'url(#' + markerId + ')');
        const focusStarted = performance.now();
        adapter.select(scene.nodes[count - 1].id); adapter.focus(); await settle();
        const focusMs = Math.round(performance.now() - focusStarted);
        const selectedRect = svg.querySelector('.editor-node[aria-pressed="true"] rect').getBoundingClientRect();
        const selectedLabel = svg.querySelector('.editor-node[aria-pressed="true"] text').getBoundingClientRect();
        const beforePan = svg.querySelector('g').getAttribute('transform');
        svg.querySelector('.editor-node').focus({ preventScroll: true });
        svg.querySelector('.editor-node').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
        await settle();
        const afterPan = svg.querySelector('g').getAttribute('transform');
        const keyboardFocusRetained = document.activeElement === svg;
        const zoomTarget = svg.querySelector('.editor-edge') || svg.querySelector('.editor-node');
        zoomTarget.dispatchEvent(new KeyboardEvent('keydown', { key: '+', bubbles: true, cancelable: true }));
        await settle();
        const afterZoom = svg.querySelector('g').getAttribute('transform');
        const measurement = { count, width, renderMs, focusMs, visibleNodes, visibleEdges, visibleSuggestions, markerIsUnique, edgesUseOwnMarker,
          initialFrame, completeModelFits, sceneUnchanged: JSON.stringify(scene) === originalScene,
          selectedNodeWidth: selectedRect.width, selectedLabelHeight: selectedLabel.height,
          keyboardPanFromNode: beforePan !== afterPan, keyboardZoomFromObject: afterPan !== afterZoom,
          keyboardZoomTarget: zoomTarget.classList.contains('editor-edge') ? 'edge' : 'node',
          keyboardFocusRetained,
          domElements: svg.querySelectorAll('*').length,
          selectedVisible: Boolean(svg.querySelector('.editor-node[aria-pressed="true"]')) };
        adapter.destroy();
        const afterDestroy = new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true });
        svg.dispatchEvent(afterDestroy);
        measurement.keyboardHandlerReleased = !afterDestroy.defaultPrevented;
        measurement.rendererDomReleased = svg.childElementCount === 0;
        measurements.push(measurement);
      } finally { adapter.destroy(); svg.remove(); }
    }
    return measurements;
  });
  for (const measurement of measurements.renderer) {
    assertReadableEditorFrame(measurement.initialFrame, `Initial ${measurement.count}-node graph at ${measurement.width}px`);
    assert.equal(measurement.completeModelFits, true, 'Explicit Fit model must still cover the complete server scene');
    assert.equal(measurement.sceneUnchanged, true, 'Local framing must not change complete scene or export geometry');
    assert.ok(measurement.selectedNodeWidth >= 180 && measurement.selectedLabelHeight >= 10,
      'Focus selection must remain readable after a complete-model fit');
    assert.ok(measurement.visibleNodes <= 200 && measurement.visibleEdges <= 400 && measurement.domElements <= 1100);
    assert.ok(measurement.visibleSuggestions > 0, 'Rendering limits must include visible focus proposals');
    assert.equal(measurement.selectedVisible, true);
    assert.equal(measurement.markerIsUnique, true, 'Concurrent editor renderers must have distinct SVG marker IDs');
    assert.equal(measurement.edgesUseOwnMarker, true, 'Edges must reference the marker owned by their renderer');
    assert.equal(measurement.keyboardPanFromNode, true, 'Node focus must permit keyboard panning');
    assert.equal(measurement.keyboardFocusRetained, true, 'Navigation must retain keyboard control as visible nodes change');
    assert.equal(measurement.keyboardZoomFromObject, true, 'Focused graph objects must permit keyboard zooming');
    if (measurement.width === 1000) assert.equal(measurement.keyboardZoomTarget, 'edge',
      'The wider viewport must retain keyboard zoom coverage from an edge');
    assert.equal(measurement.keyboardHandlerReleased, true, 'Destroy must release the keyboard handler');
    assert.equal(measurement.rendererDomReleased, true, 'Destroy must release the renderer-owned SVG content');
    assert.ok(measurement.renderMs < 1500 && measurement.focusMs < 1500,
      `Renderer exceeded its 1500 ms CI budget: ${JSON.stringify(measurement)}`);
  }
  if (role !== 'USER') {
    measurements.snapshotAdoption = await runSnapshotAdoptionAcceptance(page,base,initial);
    await page.goBack();
    await page.waitForFunction(()=>document.getElementById('architectureEditor')?.getAttribute('aria-busy')==='false');
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
