function uniqueSuffix() {
  return `${Date.now().toString(36)}-${process.pid.toString(36)}`.toUpperCase();
}

async function waitUntilIdle(page) {
  await page.locator('#portfolioBusy')
    .waitFor({ state: 'hidden', timeout: 120_000 })
    .catch(() => undefined);
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
}

/** End-to-end portfolio workflow for the authoritative ADMIN browser profile. */
export async function runPortfolioWorkflows({ page, baseUrl, evidence }) {
  const suffix = uniqueSuffix();
  const projectKey = `P-UI-${suffix}`;
  const solutionKey = `SOL-UI-${suffix}`;
  const productKey = `PRD-UI-${suffix}`;

  await page.goto(`${baseUrl}/projects`, { waitUntil: 'networkidle' });
  await page.locator('#portfolioMain').waitFor({ state: 'visible', timeout: 30_000 });
  evidence.assert(await page.locator('#projectList').getAttribute('role') === 'listbox',
    'Project portfolio list lacks listbox semantics');
  evidence.passed('portfolio page and project list semantics');

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

  await page.locator('[data-bs-target="#requirementModal"]').click();
  await submitModal(page, 'requirementModal', async modal => {
    await modal.locator('#requirementKey').fill('REQ-001');
    await modal.locator('#requirementTitle').fill('Secure portfolio analysis');
    await modal.locator('#requirementType').selectOption('SECURITY');
    await modal.locator('#requirementText').fill(
      'Provide traceable secure communication, resilient data exchange and auditable architecture decisions.');
  }, /^\/api\/projects\/\d+\/requirements$/);
  await page.locator('#requirementsTable tbody').getByText('REQ-001')
    .waitFor({ state: 'visible', timeout: 30_000 });
  evidence.passed('portfolio requirement creation');

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
  evidence.passed('portfolio solution creation');

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
  evidence.passed('portfolio sourced product creation');

  await page.locator('#requirements-tab').click();
  const responsePromise = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && /^\/api\/projects\/\d+\/analyses$/.test(new URL(response.url()).pathname),
  { timeout: 180_000 });
  await page.locator('#analyzeAllBtn').click();
  const analysisResponse = await responsePromise;
  evidence.assert(analysisResponse.ok(),
    `Portfolio analysis failed with HTTP ${analysisResponse.status()}`);
  const job = await analysisResponse.json();
  evidence.assert(job.totalItems === 1,
    `Expected one independently analysed requirement, got ${job.totalItems}`);
  evidence.assert(job.successfulItems + job.partialItems === 1,
    'Portfolio analysis did not create a successful or partial snapshot');
  await waitUntilIdle(page);
  evidence.passed('portfolio independent mock analysis');

  await page.locator('#taxonomy-tab').click();
  await page.locator('#taxonomyPane').waitFor({ state: 'visible' });
  await page.locator('#snapshots-tab').click();
  await page.locator('#snapshotsPane').waitFor({ state: 'visible' });
  evidence.passed('portfolio result tab navigation');

  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth
  }));
  evidence.assert(overflow.scrollWidth <= overflow.clientWidth + 2,
    `Portfolio overflows viewport: ${overflow.scrollWidth} > ${overflow.clientWidth}`);
  await evidence.axeState('portfolio-complete', '#portfolioMain');
  await evidence.saveState('portfolio-complete', '#portfolioMain');
  evidence.passed('portfolio reflow and evidence');
}
