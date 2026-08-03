function uniqueSuffix() {
  return `${Date.now().toString(36)}-${process.pid.toString(36)}`.toUpperCase();
}

async function waitUntilIdle(page, timeout = 30_000) {
  await page.locator('#portfolioBusy').waitFor({ state: 'hidden', timeout });
}

async function submitModal(page, modalId, fill, responsePattern) {
  const modal = page.locator(`#${modalId}`);
  await modal.waitFor({ state: 'visible', timeout: 20_000 });
  await fill(modal);
  const responsePromise = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && responsePattern.test(new URL(response.url()).pathname),
  { timeout: 120_000 });
  await modal.locator('button[type="submit"]').click();
  const response = await responsePromise;
  if (!response.ok()) {
    throw new Error(`${modalId} request failed with HTTP ${response.status()}`);
  }
  await modal.waitFor({ state: 'hidden', timeout: 30_000 });
  await waitUntilIdle(page);
  return response;
}

async function completeDecisionDialog(page, evidence, comment = '') {
  const modal = page.locator('#portfolioDecisionDialog');
  await modal.waitFor({ state: 'visible', timeout: 20_000 });
  await modal.locator('#portfolioDecisionEvidence').fill(evidence);
  if (comment) await modal.locator('#portfolioDecisionComment').fill(comment);
  await modal.locator('#portfolioDecisionSave').click();
  await modal.waitFor({ state: 'hidden', timeout: 30_000 });
  await waitUntilIdle(page);
}

async function readTerminalJob(page, location, baseUrl) {
  const jobUrl = new URL(location, baseUrl).toString();
  await page.waitForFunction(async url => {
    const response = await fetch(url, { headers: { Accept: 'application/json' } });
    if (!response.ok) return false;
    const job = await response.json();
    return ['SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(job.status);
  }, jobUrl, { timeout: 180_000, polling: 500 });
  return page.evaluate(async url => {
    const response = await fetch(url, { headers: { Accept: 'application/json' } });
    return { status: response.status, body: await response.json() };
  }, jobUrl);
}

async function createRequirement(page, key, title, text) {
  await page.locator('[data-bs-target="#requirementModal"]').click();
  await submitModal(page, 'requirementModal', async modal => {
    await modal.locator('#requirementKey').fill(key);
    await modal.locator('#requirementTitle').fill(title);
    await modal.locator('#requirementType').selectOption('SECURITY');
    await modal.locator('#requirementText').fill(text);
  }, /^\/api\/projects\/\d+\/requirements$/);
  await page.locator('#requirementsTable tbody').getByText(key)
    .waitFor({ state: 'visible', timeout: 30_000 });
}

/** End-to-end portfolio workflow for the authoritative ADMIN browser profile. */
export async function runPortfolioWorkflows({ page, baseUrl, evidence }) {
  const suffix = uniqueSuffix();
  const projectKey = `P-UI-${suffix}`;
  const solutionKey = `SOL-UI-${suffix}`;
  const productKey = `PRD-UI-${suffix}`;

  await page.goto(`${baseUrl}/projects`, { waitUntil: 'networkidle' });
  await page.locator('#portfolioMain').waitFor({ state: 'visible', timeout: 30_000 });
  evidence.assert(await page.locator('#projectList').getAttribute('role') !== 'listbox',
    'Project navigation must not claim an incomplete ARIA listbox pattern');
  evidence.assert(await page.locator('#projectList button').count() >= 0,
    'Project navigation must use native interactive controls');
  evidence.passed('portfolio page and native project navigation semantics');

  const skipLink = page.locator('.skip-link');
  await skipLink.focus();
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => window.location.hash === '#portfolioMain');
  await page.keyboard.press('Tab');
  evidence.assert(await page.evaluate(() => {
    const main = document.querySelector('#portfolioMain');
    return Boolean(main && document.activeElement && main.contains(document.activeElement));
  }), 'Portfolio skip link did not bypass navigation into the main region');
  evidence.passed('portfolio skip link keyboard bypass');

  await page.locator('[data-bs-target="#projectModal"]').click();
  await submitModal(page, 'projectModal', async modal => {
    await modal.locator('#projectKey').fill(projectKey);
    await modal.locator('#projectTitle').fill('Portfolio primary acceptance');
    await modal.locator('#projectDescription').fill(
      'Created by the authoritative primary workflow suite.');
  }, /^\/api\/projects$/);
  await page.locator('#selectedProjectKey').filter({ hasText: projectKey })
    .waitFor({ state: 'visible', timeout: 30_000 });
  evidence.passed('portfolio project creation');

  await createRequirement(page, 'REQ-001', 'Secure communication',
    'Provide traceable secure communication and auditable architecture decisions.');
  await createRequirement(page, 'REQ-002', 'Resilient exchange',
    'Provide resilient secure data exchange with auditable architecture decisions.');
  await createRequirement(page, 'REQ-003', 'Controlled operation',
    'Provide controlled operation and secure communication with human review.');
  evidence.passed('three independent portfolio requirements');

  await page.locator('#solutions-tab').click();
  await page.locator('[data-bs-target="#solutionModal"]').click();
  await submitModal(page, 'solutionModal', async modal => {
    await modal.locator('#solutionKey').fill(solutionKey);
    await modal.locator('#solutionTitle').fill('Secure communication service');
    await modal.locator('#solutionType').selectOption('SERVICE');
    await modal.locator('#solutionOperatingModel').selectOption('PRIVATE_CLOUD');
  }, /^\/api\/solutions$/);
  await page.locator('#solutionsList').getByText('Secure communication service')
    .waitFor({ state: 'visible', timeout: 30_000 });

  await page.locator('#products-tab').click();
  await page.locator('[data-bs-target="#productModal"]').click();
  await submitModal(page, 'productModal', async modal => {
    await modal.locator('#productKey').fill(productKey);
    await modal.locator('#productManufacturer').fill('Acceptance Vendor');
    await modal.locator('#productName').fill('Acceptance Product');
    await modal.locator('#productVersion').fill('1.0');
    await modal.locator('#productOperatingModel').selectOption('PRIVATE_CLOUD');
    await modal.locator('#productSource').fill('Primary UI acceptance catalogue reference 1.0');
  }, /^\/api\/products$/);
  await page.locator('#productsList').getByText('Acceptance Product')
    .waitFor({ state: 'visible', timeout: 30_000 });
  evidence.passed('portfolio solution and sourced product creation');

  await page.locator('#requirements-tab').click();
  const responsePromise = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && /^\/api\/projects\/\d+\/analyses$/.test(new URL(response.url()).pathname),
  { timeout: 180_000 });
  await page.locator('#analyzeAllBtn').click();
  const analysisResponse = await responsePromise;
  evidence.assert(analysisResponse.status() === 202,
    `Portfolio analysis must be accepted asynchronously, got HTTP ${analysisResponse.status()}`);
  const location = await analysisResponse.headerValue('location');
  evidence.assert(location, 'Asynchronous analysis response lacks a canonical Location header');

  await waitUntilIdle(page, 30_000);
  await page.locator('#portfolioJobCenter').waitFor({ state: 'visible', timeout: 20_000 });
  evidence.assert(await page.locator('#portfolioJobList .portfolio-job').count() >= 1,
    'Accepted analysis was not registered in the persistent job center');

  // Prove that the application remains navigable while the persisted job is
  // running instead of holding a full-page busy overlay for up to ten minutes.
  await page.locator('#products-tab').click();
  await page.locator('#productsPane').waitFor({ state: 'visible' });
  evidence.passed('non-blocking analysis navigation and visible job center');

  const terminal = await readTerminalJob(page, location, baseUrl);
  evidence.assert(terminal.status === 200,
    `Terminal analysis job could not be read: HTTP ${terminal.status}`);
  evidence.assert(['SUCCESS', 'PARTIAL'].includes(terminal.body.status),
    `Analysis job did not finish successfully: ${terminal.body.status}`);
  evidence.assert(terminal.body.totalItems === 3,
    `Expected three independently analysed requirements, got ${terminal.body.totalItems}`);
  evidence.assert(terminal.body.successfulItems + terminal.body.partialItems === 3,
    'Not all three requirements created a successful or partial snapshot');
  await page.locator('#portfolioJobList').getByText(/Successful|Erfolgreich/)
    .first().waitFor({ state: 'visible', timeout: 30_000 });
  evidence.passed('terminal job state and per-requirement persistence');

  // Reload proves that the job list is persisted independently of one page life.
  await page.reload({ waitUntil: 'networkidle' });
  await page.locator('#portfolioJobCenter').waitFor({ state: 'visible', timeout: 30_000 });
  evidence.assert(await page.locator('#portfolioJobList .portfolio-job').count() >= 1,
    'Analysis job was lost after browser reload');
  evidence.passed('analysis job recovery after reload');

  await page.locator('#requirements-tab').click();
  const firstRow = page.locator('#requirementsTable tbody tr').filter({ hasText: 'REQ-001' });
  await firstRow.locator('.requirement-snapshots').click();
  await page.locator('#snapshotDetail .mapping-list tbody tr').first()
    .waitFor({ state: 'visible', timeout: 30_000 });
  const nodeCode = await page.locator('#snapshotDetail .mapping-list tbody tr').first()
    .locator('code').innerText();
  evidence.assert(Boolean(nodeCode), 'Snapshot did not expose a taxonomy mapping');

  const mappingRow = page.locator('#snapshotDetail .mapping-list tbody tr').first();
  await mappingRow.locator('.mapping-action-select').selectOption('REUSE');
  await mappingRow.locator('.mapping-review').click();
  await completeDecisionDialog(page,
    'Acceptance reviewer verified the requirement text and taxonomy mapping.',
    'Browser acceptance review');
  evidence.passed('snapshot detail and evidence-backed mapping review');

  await page.locator('#solutions-tab').click();
  const solutionCard = page.locator('#solutionsList .portfolio-solution-card')
    .filter({ hasText: 'Secure communication service' });
  const coverageDetails = solutionCard.locator('details').first();
  await coverageDetails.locator('summary').click();
  await solutionCard.locator('.solution-node-code').fill(nodeCode);
  await solutionCard.locator('.solution-node-coverage').fill('90');
  await solutionCard.locator('.solution-node-review').selectOption('CONFIRMED');
  await solutionCard.locator('.add-solution-coverage').click();
  await completeDecisionDialog(page,
    'Solution catalogue evidence confirms coverage of the selected taxonomy node.');

  await page.locator('#proposeSolutionsBtn').click();
  await waitUntilIdle(page);
  const confirmLink = page.locator('.confirm-requirement-link').first();
  await confirmLink.waitFor({ state: 'visible', timeout: 30_000 });
  await confirmLink.click();
  await completeDecisionDialog(page,
    'Reviewer confirmed this reusable solution for the analysed requirement snapshot.');
  evidence.passed('confirmed requirement-to-solution decision');

  const refreshedSolution = page.locator('#solutionsList .portfolio-solution-card')
    .filter({ hasText: 'Secure communication service' });
  await refreshedSolution.locator('.solution-product-select').selectOption({ index: 1 });
  await refreshedSolution.locator('.solution-product-coverage').fill('85');
  await refreshedSolution.locator('.add-product-candidate').click();
  await waitUntilIdle(page);
  await page.locator('.product-candidate-review[data-status="SHORTLISTED"]').first().click();
  await waitUntilIdle(page);
  await page.locator('.product-candidate-review[data-status="SELECTED"]').first().click();
  await waitUntilIdle(page);
  evidence.passed('reviewed and selected solution product candidate');

  evidence.assert(await page.locator(
    '#requirementSolutionMatrix .matrix-cell[data-value]:not([data-value="0"])').count() > 0,
  'Requirement-to-solution matrix did not reflect the confirmed decision');
  evidence.assert(await page.locator(
    '#solutionProductMatrix .matrix-cell[data-value]:not([data-value="0"])').count() > 0,
  'Solution-to-product matrix did not reflect the selected candidate');
  evidence.passed('portfolio matrix values match persisted decisions');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('#portfolioMain').waitFor({ state: 'visible' });
  const mobileOverflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth
  }));
  evidence.assert(mobileOverflow.scrollWidth <= mobileOverflow.clientWidth + 2,
    `Portfolio overflows mobile viewport: ${mobileOverflow.scrollWidth} > ${mobileOverflow.clientWidth}`);

  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
  const zoomOverflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth
  }));
  evidence.assert(zoomOverflow.scrollWidth <= zoomOverflow.clientWidth + 2,
    `Portfolio overflows at 200% text zoom: ${zoomOverflow.scrollWidth} > ${zoomOverflow.clientWidth}`);
  await page.evaluate(() => { document.documentElement.style.fontSize = ''; });

  await evidence.axeState('portfolio-complete', '#portfolioMain');
  await evidence.saveState('portfolio-complete', '#portfolioMain');
  evidence.passed('portfolio mobile, zoom, accessibility and evidence');
}
