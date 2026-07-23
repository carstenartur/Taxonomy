import { navigateArchitectureSubtab, navigateToPage } from './ui-role-fixtures.mjs';

export async function runImportWorkflows({ page, evidence }) {
  const { assert, passed, axeState, saveState, waitForText } = evidence;
  await navigateToPage(page, 'analyze');
  await page.route('**/api/documents/upload', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ fileName: 'requirements.pdf', mimeType: 'application/pdf',
      totalPages: 1, totalCandidates: 2, sourceArtifactId: 42,
      candidates: [
        { text: 'The service shall remain available.', sectionHeading: 'Availability', pageNumber: 1 },
        { text: 'The service shall record decisions.', sectionHeading: 'Traceability', pageNumber: 1 }
      ] })
  }), { times: 1 });
  await page.locator('#docImportFile').setInputFiles({
    name: 'requirements.pdf', mimeType: 'application/pdf', buffer: Buffer.from('mock-pdf')
  });
  await page.locator('#docImportUploadBtn').click();
  await page.locator('#docCandidateReviewPanel').waitFor({ state: 'visible', timeout: 15_000 });
  assert((await page.locator('#docCandidateCount').textContent()).trim() === '2',
    'Candidate count is not two');
  await axeState('document-review-success', '#docCandidateReviewPanel');
  await saveState('document-review-success', '#docCandidateReviewPanel');
  passed('document upload and candidate review');

  await page.route('**/api/documents/upload', route => route.fulfill({
    status: 422,
    contentType: 'application/json',
    body: JSON.stringify({ error: 'DOCUMENT_RESOURCE_LIMIT',
      message: 'Expanded document exceeds the configured limit' })
  }), { times: 1 });
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
  await page.route('**/api/import/preview/apqc', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, profileId: 'apqc', profileDisplayName: 'APQC PCF',
      elementsTotal: 1, elementsMapped: 1, relationsTotal: 0, relationsMapped: 0,
      unmappedTypes: [], warnings: [] })
  }), { times: 1 });
  await page.locator('#importPreviewBtn').click();
  await page.locator('#importResultArea table').waitFor({ state: 'visible', timeout: 15_000 });
  await axeState('framework-import-preview', '#importResultArea');
  await saveState('framework-import-preview', '#importResultArea');
  passed('framework import preview');

  await page.route('**/api/import/apqc?branch=*', route => route.fulfill({
    status: 422,
    contentType: 'application/json',
    body: JSON.stringify({ error: 'INVALID_IMPORT',
      message: 'The selected framework file is inconsistent' })
  }), { times: 1 });
  await page.locator('#importExecuteBtn').click();
  await page.locator('#importResultArea .alert-danger').waitFor({ state: 'visible', timeout: 15_000 });
  await axeState('framework-import-error', '#importResultArea');
  await saveState('framework-import-error', '#importResultArea');
  passed('framework import error feedback');
}
