import { navigateArchitectureSubtab, navigateToPage } from './ui-role-fixtures.mjs';

export async function runImportWorkflows({ page, evidence }) {
  const { assert, passed, axeState, saveState, waitForText } = evidence;
  await navigateToPage(page, 'analyze');
  const documentPanel = page.locator('#documentImportPanel');
  if (!(await documentPanel.getAttribute('open'))) {
    await documentPanel.locator('summary').click();
  }
  await page.locator('#docImportUploadBtn').waitFor({ state: 'visible', timeout: 10_000 });

  const successBody = JSON.stringify({ fileName: 'requirements.pdf', mimeType: 'application/pdf',
    totalPages: 1, totalCandidates: 2, sourceArtifactId: 42,
    candidates: [
      { text: 'The service shall remain available.', sectionHeading: 'Availability', pageNumber: 1 },
      { text: 'The service shall record decisions.', sectionHeading: 'Traceability', pageNumber: 1 }
    ] });
  await page.route('**/api/documents/upload', async route => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: successBody });
  }, { times: 1 });
  await page.locator('#docImportFile').setInputFiles({
    name: 'requirements.pdf', mimeType: 'application/pdf', buffer: Buffer.from('mock-pdf')
  });
  const requestPromise = page.waitForRequest(request => {
    const url = new URL(request.url());
    return request.method() === 'POST' && url.pathname === '/api/documents/upload';
  }, { timeout: 15_000 });
  const responsePromise = page.waitForResponse(response => {
    const url = new URL(response.url());
    return response.request().method() === 'POST' && url.pathname === '/api/documents/upload';
  }, { timeout: 15_000 });
  await page.locator('#docImportUploadBtn').click();
  const request = await requestPromise;
  const response = await responsePromise;
  await response.finished();
  const network = {
    method: request.method(),
    url: request.url(),
    status: response.status(),
    contentType: response.headers()['content-type'] || null,
    body: await response.text()
  };
  assert(network.status === 200, `Document upload network response failed: ${JSON.stringify(network)}`);

  let outcome;
  try {
    const outcomeHandle = await page.waitForFunction(() => {
      const status = document.getElementById('docImportStatus');
      const text = (status?.textContent || '').trim();
      if (!text || /uploading|parsing/i.test(text)) return false;
      const panel = document.getElementById('docCandidateReviewPanel');
      const selected = document.querySelector('input[name="importMode"]:checked');
      return {
        success: Boolean(status.querySelector('.text-success')),
        statusText: text,
        statusHtml: status.innerHTML,
        mode: selected?.value || null,
        panelStyle: panel?.getAttribute('style'),
        panelClass: panel?.className,
        panelAriaHidden: panel?.getAttribute('aria-hidden'),
        semanticsLoaded: Boolean(window.TaxonomyUiSemantics),
        statusObserved: status.dataset.resultPanelObserved || null,
        lifecycleObserved: document.getElementById('docImportUploadBtn')?.dataset.resultLifecycleObserved || null
      };
    }, null, { timeout: 15_000 });
    outcome = await outcomeHandle.jsonValue();
  } catch (error) {
    outcome = await page.evaluate(() => {
      const status = document.getElementById('docImportStatus');
      const panel = document.getElementById('docCandidateReviewPanel');
      const selected = document.querySelector('input[name="importMode"]:checked');
      return {
        success: Boolean(status?.querySelector('.text-success')),
        statusText: (status?.textContent || '').trim(),
        statusHtml: status?.innerHTML || null,
        mode: selected?.value || null,
        panelStyle: panel?.getAttribute('style'),
        panelClass: panel?.className,
        panelAriaHidden: panel?.getAttribute('aria-hidden'),
        semanticsLoaded: Boolean(window.TaxonomyUiSemantics),
        statusObserved: status?.dataset.resultPanelObserved || null,
        lifecycleObserved: document.getElementById('docImportUploadBtn')?.dataset.resultLifecycleObserved || null
      };
    });
    throw new Error(`Document upload UI did not settle; network=${JSON.stringify(network)}, outcome=${JSON.stringify(outcome)}; ${error}`);
  }
  assert(outcome.success,
    `Document upload did not succeed; network=${JSON.stringify(network)}, outcome=${JSON.stringify(outcome)}`);
  await page.locator('#docCandidateReviewPanel').waitFor({ state: 'visible', timeout: 15_000 });
  assert((await page.locator('#docCandidateCount').textContent()).trim() === '2',
    'Candidate count is not two');
  await axeState('document-review-success', '#docCandidateReviewPanel');
  await saveState('document-review-success', '#docCandidateReviewPanel');
  passed('document upload and candidate review');

  await page.route('**/api/documents/upload', async route => {
    await route.fulfill({
      status: 422,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'DOCUMENT_RESOURCE_LIMIT',
        message: 'Expanded document exceeds the configured limit' })
    });
  }, { times: 1 });
  await page.locator('#docImportFile').setInputFiles({
    name: 'oversized.pdf', mimeType: 'application/pdf', buffer: Buffer.from('oversized')
  });
  await page.locator('#docImportUploadBtn').click();
  await waitForText('#docImportStatus', text => text.includes('DOCUMENT_RESOURCE_LIMIT'));
  await axeState('document-upload-error', '#docImportPanel');
  await saveState('document-upload-error', '#docImportPanel');
  passed('document upload resource error');

  await navigateArchitectureSubtab(page, 'export');
  await page.waitForFunction(() =>
    document.querySelectorAll('#importProfileSelect option').length > 1,
  null, { timeout: 20_000 });
  await page.locator('#importProfileSelect').selectOption('apqc');
  await page.locator('#importFrameworkFile').setInputFiles({
    name: 'apqc.csv', mimeType: 'text/csv',
    buffer: Buffer.from('PCF ID,Name,Level,Description\n1.0,Strategy,1,QA')
  });
  await page.route('**/api/import/preview/apqc', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, profileId: 'apqc', profileDisplayName: 'APQC PCF',
        elementsTotal: 1, elementsMapped: 1, relationsTotal: 0, relationsMapped: 0,
        unmappedTypes: [], warnings: [] })
    });
  }, { times: 1 });
  await page.locator('#importPreviewBtn').click();
  await page.locator('#importResultArea table').waitFor({ state: 'visible', timeout: 15_000 });
  await axeState('framework-import-preview', '#importResultArea');
  await saveState('framework-import-preview', '#importResultArea');
  passed('framework import preview');

  await page.route('**/api/import/apqc?branch=*', async route => {
    await route.fulfill({
      status: 422,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'INVALID_IMPORT',
        message: 'The selected framework file is inconsistent' })
    });
  }, { times: 1 });
  await page.locator('#importExecuteBtn').click();
  await page.locator('#importResultArea .alert-danger').waitFor({ state: 'visible', timeout: 15_000 });
  await axeState('framework-import-error', '#importResultArea');
  await saveState('framework-import-error', '#importResultArea');
  passed('framework import error feedback');
}
