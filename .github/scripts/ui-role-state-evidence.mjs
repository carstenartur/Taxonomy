import AxeBuilder from '@axe-core/playwright';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { createEvidencePolicy, screenshotStrategy } from './ui-evidence-policy.mjs';

export function createRoleStateEvidence({ page, outputDir, checks, findings }) {
  const policy = createEvidencePolicy();
  const states = [];
  let analysisDraftBarrierRecorded = false;

  async function persistAnalysisDraftBeforeBlockingEvidence(state) {
    if (state !== 'analysis-success' && !analysisDraftBarrierRecorded) return;
    const result = await page.evaluate(async required => {
      const session = window.TaxonomyAnalysisSession;
      const context = window.__TaxonomyAnalysisSessionContext;
      // Separate destination pages do not own the analysis draft. An analysis
      // loader or a requested success audit still requires the complete contract.
      if (!required && !session && !context
          && !document.querySelector('script[data-taxonomy-analysis-session]')) return null;
      const runtime = context?.runtime;
      if (!session || typeof session.saveNow !== 'function' || typeof session.state !== 'function'
          || !runtime?.workspaceId
          || typeof context.currentPayload !== 'function'
          || typeof context.comparable !== 'function') {
        throw new Error('Analysis draft persistence contract is unavailable');
      }

      const workspaceId = runtime.workspaceId;
      const scope = () => JSON.stringify([runtime.workspaceId, runtime.analysisGeneration,
        runtime.draftGeneration, runtime.draftAcknowledgementGeneration]);
      const initialScope = scope();
      function requireReady() {
        const state = session.state();
        if (!state?.ready || state.workspaceId !== workspaceId || scope() !== initialScope
            || runtime.conflict || runtime.restoring || runtime.invalidating || runtime.resetting
            || runtime.serverUnconfirmed || runtime.draftDecisionPending) {
          throw new Error('Analysis draft changed or is not ready for evidence capture');
        }
      }
      function cleanPayload() {
        requireReady();
        const payload = context.currentPayload();
        if (runtime.saveInFlight || runtime.saveQueued
            || context.comparable(payload) !== runtime.lastSavedComparable) {
          throw new Error('Analysis draft has an unsaved change before evidence capture');
        }
        return payload;
      }
      function sourceIdentity(payload) {
        const reference = payload?.analysisReference;
        if (reference?.workspaceId === workspaceId && reference.continuationId) {
          return JSON.stringify(['reference', workspaceId, reference.continuationId,
            reference.operationId, reference.recoveryVersion]);
        }
        const recovery = payload?.recoveryContext;
        return recovery?.workspaceId === workspaceId && recovery.id
          ? JSON.stringify(['recovery', workspaceId, recovery.id]) : null;
      }

      requireReady();
      const saved = await session.saveNow();
      if (saved !== true) {
        throw new Error('Analysis draft did not reach a confirmed saved state');
      }
      const before = cleanPayload();
      const localVersion = runtime.version;
      const identity = sourceIdentity(before);
      if (!Number.isSafeInteger(localVersion) || localVersion < 0) {
        throw new Error('Analysis draft has no confirmed revision before evidence capture');
      }
      // Read-only recovery observations may rebase a clean browser payload
      // without writing another draft revision. Preserve the complete receipt
      // of this exact source/version before awaiting the independent server GET.
      const acknowledged = (runtime.acknowledgedDraftWrites || []).find(entry => {
        if (!identity || entry.version !== localVersion || typeof entry.serialized !== 'string') return false;
        try { return sourceIdentity(JSON.parse(entry.serialized)) === identity; }
        catch { return false; }
      });
      const acknowledgedPayload = acknowledged?.serialized;

      const response = await fetch(
        `/api/analysis-drafts/${encodeURIComponent(workspaceId)}`,
        {
          method: 'GET',
          headers: { Accept: 'application/json', 'Cache-Control': 'no-cache' },
          cache: 'no-store'
        }
      );
      if (!response.ok) {
        throw new Error(`Analysis draft verification returned HTTP ${response.status}`);
      }
      const view = await response.json();
      const after = cleanPayload();
      if (runtime.version !== localVersion || sourceIdentity(after) !== identity) {
        throw new Error('Analysis draft revision or source changed during evidence verification');
      }
      return {
        workspaceId,
        localVersion,
        remoteVersion: view.version,
        comparison: acknowledgedPayload === undefined ? 'current-payload' : 'acknowledged-write',
        equivalent: view.workspaceId === workspaceId && context.comparable(view.payload)
          === (acknowledgedPayload ?? context.comparable(after))
      };
    }, state === 'analysis-success');

    if (result === null) return;
    if (!result.equivalent
        || Number(result.localVersion) !== Number(result.remoteVersion)) {
      throw new Error(
        `Analysis draft persistence barrier diverged: ${JSON.stringify(result)}`);
    }
    if (!analysisDraftBarrierRecorded) {
      analysisDraftBarrierRecorded = true;
      checks.push('authoritative analysis draft before blocking evidence');
    }
  }

  async function measureTaxonomyTreeViewport() {
    return page.evaluate(() => {
      const tree = document.getElementById('taxonomyTree');
      const scroller = tree?.closest('.card-body');
      if (!scroller || scroller.offsetParent === null) return null;
      const style = window.getComputedStyle(scroller);
      const viewportHeight = window.innerHeight;
      const clientHeight = scroller.clientHeight;
      const scrollHeight = scroller.scrollHeight;
      const overflowY = style.overflowY;
      const maxAllowedHeight = Math.ceil(viewportHeight * 0.9);
      return {
        clientHeight,
        scrollHeight,
        viewportHeight,
        maxAllowedHeight,
        computedMaxHeight: style.maxHeight,
        overflowY,
        bounded: clientHeight <= maxAllowedHeight,
        scrollable: scrollHeight <= clientHeight + 1 || ['auto', 'scroll'].includes(overflowY)
      };
    });
  }

  async function saveState(state) {
    const prefix = path.join(outputDir, state);
    const dimensions = await page.evaluate(() => ({
      width: Math.max(document.documentElement.scrollWidth, document.body?.scrollWidth || 0),
      height: Math.max(document.documentElement.scrollHeight, document.body?.scrollHeight || 0)
    }));
    const viewport = page.viewportSize() || {
      width: Math.min(dimensions.width, 1440),
      height: Math.min(dimensions.height, 1000)
    };
    const taxonomyTreeViewport = await measureTaxonomyTreeViewport();
    const documentHeightViewportRatio = Number(
      (dimensions.height / Math.max(1, viewport.height)).toFixed(4));
    const captured = policy.shouldCapture(state);
    const strategy = screenshotStrategy({
      mode: policy.mode,
      captured,
      width: dimensions.width,
      height: dimensions.height
    });
    const screenshotFiles = [];
    const segments = [];

    if (taxonomyTreeViewport
        && (!taxonomyTreeViewport.bounded || !taxonomyTreeViewport.scrollable)) {
      const stateSummary = {
        state,
        evidenceMode: policy.mode,
        captureStrategy: 'layout-gate',
        captured: false,
        dimensions,
        viewport,
        taxonomyTreeViewport,
        documentHeightViewportRatio,
        screenshotFiles,
        segments
      };
      states.push(stateSummary);
      await writeFile(
        `${prefix}.screenshots.json`,
        `${JSON.stringify(stateSummary, null, 2)}\n`,
        'utf8'
      );
      throw new Error(
        `Taxonomy tree viewport is unbounded in ${state}: `
        + `${taxonomyTreeViewport.clientHeight}px for a `
        + `${taxonomyTreeViewport.viewportHeight}px viewport, `
        + `max-height=${taxonomyTreeViewport.computedMaxHeight}, `
        + `overflow-y=${taxonomyTreeViewport.overflowY}`
      );
    }

    if (state === 'analysis-success' && documentHeightViewportRatio > 25) {
      throw new Error(
        `Analysis page height is pathological: ${dimensions.height}px / ${viewport.height}px `
        + `= ${documentHeightViewportRatio} viewports`);
    }

    if (strategy === 'viewport') {
      const mainContent = page.locator('#mainContent');
      if (await mainContent.count()) await mainContent.scrollIntoViewIfNeeded();
      const actualY = await page.evaluate(() => window.scrollY);
      const file = `${prefix}.png`;
      await page.screenshot({ path: file, fullPage: false, animations: 'disabled' });
      screenshotFiles.push(path.basename(file));
      segments.push({
        file: path.basename(file),
        scrollY: actualY,
        width: viewport.width,
        height: viewport.height,
        fullPage: false
      });
    } else if (strategy === 'full-page') {
      const file = `${prefix}.png`;
      await page.screenshot({ path: file, fullPage: true, animations: 'disabled' });
      screenshotFiles.push(path.basename(file));
      segments.push({ file: path.basename(file), scrollY: 0, fullPage: true });
    } else if (strategy === 'segmented') {
      const maxScrollY = Math.max(0, dimensions.height - viewport.height);
      const targets = [];
      for (let y = 0; y < maxScrollY; y += viewport.height) targets.push(y);
      if (targets.length === 0 || targets.at(-1) !== maxScrollY) targets.push(maxScrollY);
      for (let index = 0; index < targets.length; index += 1) {
        const requestedY = targets[index];
        const actualY = await page.evaluate(y => {
          window.scrollTo(0, y);
          return window.scrollY;
        }, requestedY);
        await page.waitForTimeout(50);
        const file = `${prefix}.part-${String(index + 1).padStart(2, '0')}.png`;
        await page.screenshot({ path: file, fullPage: false, animations: 'disabled' });
        screenshotFiles.push(path.basename(file));
        segments.push({ file: path.basename(file), requestedY, scrollY: actualY,
          width: viewport.width, height: viewport.height });
      }
      await page.evaluate(() => window.scrollTo(0, 0));
    }

    const stateSummary = {
      state,
      evidenceMode: policy.mode,
      captureStrategy: strategy,
      captured,
      dimensions,
      viewport,
      taxonomyTreeViewport,
      documentHeightViewportRatio,
      screenshotFiles,
      segments
    };
    states.push(stateSummary);
    await writeFile(`${prefix}.screenshots.json`, `${JSON.stringify(stateSummary, null, 2)}\n`, 'utf8');

    if (!captured) return;
    await writeFile(`${prefix}.html`, await page.content(), 'utf8');
    const body = page.locator('body');
    const aria = typeof body.ariaSnapshot === 'function'
      ? await body.ariaSnapshot().catch(error => `ARIA snapshot unavailable: ${error}`)
      : 'ARIA snapshot API unavailable';
    await writeFile(`${prefix}.aria.txt`, `${aria}\n`, 'utf8');
  }

  async function runAxe(state) {
    // Axe can occupy the main thread longer than the transport deadline. Drain
    // passive observations before the audit and defer new polls until it finishes.
    let result;
    try {
      await page.evaluate(() => window.__taxonomyRoleStateGitPolls?.pause());
      await persistAnalysisDraftBeforeBlockingEvidence(state);
      result = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
    } finally {
      if (!page.isClosed()) {
        await page.evaluate(() => window.__taxonomyRoleStateGitPolls?.resume());
      }
    }
    const blocking = result.violations.filter(violation =>
      ['critical', 'serious', 'moderate'].includes(violation.impact));
    findings.push({ state, violations: result.violations, blocking });
    if (blocking.length) {
      const summary = blocking.map(item => `${item.impact}:${item.id}`).join(', ');
      throw new Error(`Blocking axe findings in ${state}: ${summary}`);
    }
    checks.push(`axe ${state}`);
  }

  return {
    saveState,
    runAxe,
    evidenceMode: policy.mode,
    curatedStates: policy.curatedStates,
    states
  };
}
